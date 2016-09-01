package deployment

import java.util.UUID

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.agent.Agent
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import conf.{Configuration, TaskMetrics}
import controllers.Logging
import magenta._
import magenta.artifact.S3Artifact
import magenta.graph.{DeploymentGraph, DeploymentNode, StartNode}
import magenta.json.JsonReader
import org.joda.time.DateTime
import resources.LookupSelector

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

object DeployControlActor extends Logging {
  trait Event
  case class Deploy(record: Record) extends Event

  val concurrentDeploys = conf.Configuration.concurrency.maxDeploys

  lazy val dispatcherConfig = ConfigFactory.parseMap(
    Map(
      "akka.deploy-dispatcher.type" -> "akka.dispatch.BalancingDispatcherConfigurator",
      "akka.deploy-dispatcher.executor" -> "thread-pool-executor",
      "akka.deploy-dispatcher.thread-pool-executor.core-pool-size-min" -> ("%d" format concurrentDeploys),
      "akka.deploy-dispatcher.thread-pool-executor.core-pool-size-factor" -> ("%d" format concurrentDeploys),
      "akka.deploy-dispatcher.thread-pool-executor.core-pool-size-max" -> ("%d" format concurrentDeploys * 4),
      "akka.deploy-dispatcher.thread-pool-executor.max-pool-size-min" -> ("%d" format concurrentDeploys),
      "akka.deploy-dispatcher.thread-pool-executor.max-pool-size-factor" -> ("%d" format concurrentDeploys),
      "akka.deploy-dispatcher.thread-pool-executor.max-pool-size-max" -> ("%d" format concurrentDeploys * 4),
      "akka.deploy-dispatcher.throughput" -> "100"
    )
  )
  lazy val system = ActorSystem("deploy", dispatcherConfig.withFallback(ConfigFactory.load()))

  lazy val stopFlagAgent = Agent(Map.empty[UUID, String])(system.dispatcher)

  lazy val deploymentRunnerFactory = (context: ActorRefFactory, runnerName: String) => context.actorOf(
    props = Props(classOf[DeploymentRunner], stopFlagAgent).withDispatcher("akka.deploy-dispatcher"),
    name = s"deploymentRunner-$runnerName"
  )

  lazy val deployRunnerFactory = (context: ActorRefFactory, record: Record, deployCoordinator: ActorRef) =>
    context.actorOf(
      props = Props(classOf[DeployGroupRunner], record, deployCoordinator, deploymentRunnerFactory, stopFlagAgent).withDispatcher("akka.deploy-dispatcher"),
      name = s"deployGroupRunner-${record.uuid.toString}"
    )

  lazy val deployCoordinator = system.actorOf(Props(
    classOf[DeployCoordinator], deployRunnerFactory, concurrentDeploys, stopFlagAgent
  ), name = "deployCoordinator")

  def interruptibleDeploy(record: Record) {
    log.debug("Sending start deploy message to co-ordinator")
    deployCoordinator ! DeployCoordinator.StartDeploy(record)
  }

  def stopDeploy(uuid: UUID, userName: String) {
    stopFlagAgent.send(_ + (uuid -> userName))
  }

  def getDeployStopFlag(uuid: UUID): Boolean = {
    stopFlagAgent().contains(uuid)
  }
}

object DeployMetricsActor {
  trait Message
  case class TaskStart(deployId: UUID, taskId: String, queueTime: DateTime, startTime: DateTime) extends Message
  case class TaskComplete(deployId: UUID, taskId: String, finishTime: DateTime) extends Message
  case class TaskCountRequest() extends Message

  lazy val system = ActorSystem("deploy-metrics")
  lazy val deployMetricsProcessor = system.actorOf(Props[DeployMetricsActor])
  def runningTaskCount: Int = {
    implicit val timeout = Timeout(100 milliseconds)
    val count = deployMetricsProcessor ? TaskCountRequest() mapTo manifest[Int]
    Try {
      Await.result(count, timeout.duration)
    } getOrElse 0
  }
}

class DeployMetricsActor extends Actor with Logging {
  var runningTasks = Map.empty[(UUID, String), DateTime]
  import DeployMetricsActor._
  def receive = {
    case TaskStart(deployId, taskId, queueTime, startTime) =>
      TaskMetrics.TaskStartLatency.recordTimeSpent(startTime.getMillis - queueTime.getMillis)
      runningTasks += ((deployId, taskId) -> startTime)
    case TaskComplete(deployId, taskId, finishTime) =>
      runningTasks.get((deployId, taskId)).foreach { startTime =>
        TaskMetrics.TaskTimer.recordTimeSpent(finishTime.getMillis - startTime.getMillis)
      }
      runningTasks -= (deployId -> taskId)
    case TaskCountRequest() =>
      sender ! runningTasks.size
  }
}

object DeployGroupRunner {
  sealed trait NextResult
  case class Deployments(deployments: List[DeploymentNode]) extends NextResult
  case class FinishPath() extends NextResult
  case class FinishDeploy() extends NextResult

  sealed trait Message
  case class Start() extends Message
  case class ContextCreated(context: DeployContext) extends Message
  case class StartDeployment() extends Message
  case class DeploymentCompleted(deploymentNode: DeploymentNode) extends Message
  case class DeploymentFailed(deploymentNode: DeploymentNode, exception: Throwable) extends Message
}

case class DeployGroupRunner(
  record: Record,
  deployCoordinator: ActorRef,
  deploymentRunnerFactory: (ActorRefFactory, String) => ActorRef,
  stopFlagAgent: Agent[Map[UUID, String]]
) extends Actor with Logging {
  import DeployGroupRunner._

  override def supervisorStrategy() = OneForOneStrategy() {
    case throwable =>
      log.warn("DeploymentRunner died with exception", throwable)
      Stop
  }

  val rootReporter = DeployReporter.startDeployContext(DeployReporter.rootReporterFor(record.uuid, record.parameters))
  var rootContextClosed = false

  var deployContext: Option[DeployContext] = None

  var executing: Set[DeploymentNode] = Set.empty
  var completed: Set[DeploymentNode] = Set.empty
  var failed: Set[DeploymentNode] = Set.empty

  def deploymentGraph: DeploymentGraph = deployContext.map(_.tasks).getOrElse(DeploymentGraph.empty)
  def allDeployments = deploymentGraph.nodes.filterDeploymentNodes

  def isFinished: Boolean = allDeployments == completed ++ failed
  def isExecuting: Boolean = executing.nonEmpty

  def firstDeployments: List[DeploymentNode] = deploymentGraph.successorDeploymentNodes(StartNode)
  /* these two functions can return a number of things
      - Deployments: list of deployments
      - FinishPath: indicator there are no more tasks on this path
      - FinishDeploy: indicator that there are no more tasks for this deploy
      first will actually only ever return the first of these.  */
  def nextDeployments(deployment: DeploymentNode): NextResult = {
    // if this was a last node and there is no other nodes executing then there is nothing left to do
    if (isFinished) FinishDeploy()
    // otherwise let's see what children are valid to return
    else {
      // candidates are all successors not already executing or completing
      val nextDeploymentCandidates = deploymentGraph.successorDeploymentNodes(deployment)
      // now filter for only tasks whose predecessors are all completed
      val nextDeployments = nextDeploymentCandidates.filter { deployment => (deploymentGraph.predecessors(deployment) -- completed).isEmpty }
      if (nextDeployments.nonEmpty) {
        Deployments(nextDeployments)
      } else {
        FinishPath()
      }
    }
  }
  protected[deployment] def markExecuting(deployment: DeploymentNode) = {
    executing += deployment
  }
  protected[deployment] def markComplete(deployment: DeploymentNode) = {
    executing -= deployment
    completed += deployment
  }
  protected[deployment] def markFailed(deployment: DeploymentNode) = {
    executing -= deployment
    failed += deployment
  }
  def finishRootContext() = if (!rootContextClosed) {
    rootContextClosed = true
    DeployReporter.finishContext(rootReporter)
  }
  def failRootContext() = if (!rootContextClosed) {
    rootContextClosed = true
    DeployReporter.failContext(rootReporter)
  }
  def failRootContext(message: String, exception: Throwable) = if (!rootContextClosed) {
    rootContextClosed = true
    DeployReporter.failContext(rootReporter, message, exception)
  }
  private def cleanup = {
    finishRootContext()
    deployCoordinator ! DeployCoordinator.CleanupDeploy(record.uuid)
    context.stop(self)
  }

  override def toString: String = {
    s"""
       |UUID: ${record.uuid.toString}
       |#Tasks: ${allDeployments.size}
       |#Executing: ${executing.mkString("; ")}
       |#Completed: ${completed.size} Failed: ${failed.size}
       |#Done: ${completed.size+failed.size}
     """.stripMargin
  }

  def receive = {
    case Start() =>
      try {
        self ! ContextCreated(createContext)
        self ! StartDeployment()
      } catch {
        case NonFatal(t) =>
          failRootContext("Preparing deploy failed", t)
          cleanup
      }

    case ContextCreated(preparedContext) =>
      deployContext = Some(preparedContext)

    case StartDeployment() =>
      runDeployments(firstDeployments)

    case DeploymentCompleted(deployment) =>
      log.debug("Deployment completed")
      markComplete(deployment)
      nextDeployments(deployment) match {
        case Deployments(deployments) =>
          runDeployments(deployments)
        case FinishPath() =>
        case FinishDeploy() =>
          cleanup
      }

    case DeploymentFailed(deployment, exception) =>
      log.debug("Deployment failed")
      markFailed(deployment)
      if (isExecuting) {
        log.debug("Failed during deployment but others still running - deferring clean up")
      } else {
        cleanup
      }

    case Terminated(actor) =>
      failRootContext("DeploymentRunner unexpectedly terminated", new RuntimeException("DeploymentRunner unexpectedly terminated"))
      log.warn(s"Received terminate from ${actor.path}")
  }

  private def createContext: DeployContext = {
    DeployReporter.withFailureHandling(rootReporter) { implicit safeReporter =>
      import Configuration.artifact.aws._
      safeReporter.info("Reading deploy.json")
      val s3Artifact = S3Artifact(record.parameters.build, bucketName)
      val json = S3Artifact.withZipFallback(s3Artifact) { artifact =>
        Try(artifact.deployObject.fetchContentAsString()(client).get)
      }(client, safeReporter)
      val project = JsonReader.parse(json, s3Artifact)
      val context = record.parameters.toDeployContext(record.uuid, project, LookupSelector(), safeReporter, client)
      if (context.tasks.toTaskList.isEmpty)
        safeReporter.fail("No tasks were found to execute. Ensure the app(s) are in the list supported by this stage/host.")
      context
    }
  }

  private def runDeployments(deployments: List[DeploymentNode]) = {
    log.debug(s"Running next deployments: $deployments")
    try {
      honourStopFlag(rootReporter) {
        deployments.foreach { deployment =>
          val deploymentRunner = context.watch(deploymentRunnerFactory(context, s"${record.uuid}-${deployment.pathName}-${deployment.priority}"))
          deploymentRunner ! DeploymentRunner.RunDeployment(record.uuid, deployment, rootReporter, new DateTime())
          markExecuting(deployment)
        }
      }
    } catch {
      case NonFatal(t) => log.error("Couldn't run deployment", t)
    }
  }

  private def honourStopFlag(reporter: DeployReporter)(elseBlock: => Unit) {
    stopFlagAgent().get(record.uuid) match {
      case Some(userName) =>
        log.debug("Stop flag set")
        val stopMessage = s"Deploy has been stopped by $userName"
        if (!isExecuting) {
          DeployReporter.failContext(rootReporter, stopMessage, DeployStoppedException(stopMessage))
          log.debug("Cleaning up")
          cleanup
        }

      case None =>
        elseBlock
    }
  }

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    failRootContext()
    super.postStop()
  }
}

object DeployCoordinator {
  trait Message
  case class StartDeploy(record: Record) extends Message
  case class CleanupDeploy(uuid: UUID) extends Message
}

class DeployCoordinator(
  val deployGroupRunnerFactory: (ActorRefFactory, Record, ActorRef) => ActorRef,
  maxDeploys: Int, stopFlagAgent: Agent[Map[UUID, String]]
) extends Actor with Logging {

  override def supervisorStrategy() = OneForOneStrategy() {
    case throwable =>
      log.warn("DeployGroupRunner died with exception", throwable)
      Stop
  }

  var deployRunners = Map.empty[UUID, (Record, ActorRef)]
  var deferredDeployQueue = ListBuffer[DeployCoordinator.Message]()

  private def schedulable(recordToSchedule: Record): Boolean = {
    deployRunners.size < maxDeploys &&
      !deployRunners.values.exists{ case (record, actor) =>
        record.parameters.build.projectName == recordToSchedule.parameters.build.projectName &&
          record.parameters.stage == recordToSchedule.parameters.stage
      }
  }

  private def cleanup(uuid: UUID) {
    log.debug("Cleaning up")

    deferredDeployQueue.foreach(self ! _)
    deferredDeployQueue.clear()

    deployRunners -= uuid
  }

  import DeployCoordinator._

  def receive = {
    case StartDeploy(record) if !schedulable(record) =>
      log.debug("Not schedulable, queuing")
      deferredDeployQueue += StartDeploy(record)

    case StartDeploy(record) if schedulable(record) =>
      log.debug("Scheduling deploy")
      try {
        val deployGroupRunner = context.watch(deployGroupRunnerFactory(context, record, context.self))
        deployRunners += (record.uuid -> (record, deployGroupRunner))
        deployGroupRunner ! DeployGroupRunner.Start()
      } catch {
        case NonFatal(t) => log.error("Couldn't schedule deploy", t)
      }

    case CleanupDeploy(uuid) =>
      cleanup(uuid)

    case Terminated(actor) =>
      val maybeUUID = deployRunners.find{case (_, (_, ref)) => ref == actor}.map(_._1)
      log.warn(s"Received terminate from ${actor.path} (found $maybeUUID)")
      maybeUUID.foreach(cleanup)

  }
}

object DeploymentRunner {
  trait Message
  case class RunDeployment(uuid: UUID, deployment: DeploymentNode, rootReporter: DeployReporter, queueTime: DateTime) extends Message
}

class DeploymentRunner(stopFlagAgent: Agent[Map[UUID, String]]) extends Actor with Logging {
  import DeploymentRunner._

  def receive = {
    case RunDeployment(uuid, deploymentNode, rootReporter, queueTime) =>
      import DeployMetricsActor._

      def stopFlagAsker: Boolean = {
        stopFlagAgent().contains(uuid)
      }

      rootReporter.infoContext(s"Deployment ${deploymentNode.pathName}"){ deployReporter =>
        try {
          deploymentNode.tasks.zipWithIndex.foreach { case (task, index) =>
            val taskId = s"${deploymentNode.pathName}/$index"
            try {
              log.debug(s"Running task $taskId")
              deployMetricsProcessor ! TaskStart(uuid, taskId, queueTime, new DateTime())
              deployReporter.taskContext(task) { taskReporter =>
                task.execute(taskReporter, stopFlagAsker)
              }
            } finally {
              deployMetricsProcessor ! TaskComplete(uuid, taskId, new DateTime())
            }
          }
          log.debug("Sending completed message")
          sender ! DeployGroupRunner.DeploymentCompleted(deploymentNode)
        } catch {
          case t:Throwable =>
            log.debug("Sending failed message")
            sender ! DeployGroupRunner.DeploymentFailed(deploymentNode, t)
        }
      }
      context.stop(self)

  }
}