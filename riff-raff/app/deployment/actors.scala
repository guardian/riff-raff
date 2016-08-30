package deployment

import java.util.UUID

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import akka.agent.Agent
import akka.pattern.ask
import akka.routing.RoundRobinPool
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import conf.{Configuration, TaskMetrics}
import controllers.Logging
import magenta._
import magenta.artifact.S3Artifact
import magenta.json.JsonReader
import magenta.tasks._
import org.joda.time.DateTime
import resources.LookupSelector

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try
import scalax.collection.constrained.Graph

object DeployControlActor extends Logging {
  trait Event
  case class Deploy(record: Record) extends Event

  val concurrentDeploys = conf.Configuration.concurrency.maxDeploys

  lazy val dispatcherConfig = ConfigFactory.parseMap(
    Map(
      "akka.task-dispatcher.type" -> "akka.dispatch.BalancingDispatcherConfigurator",
      "akka.task-dispatcher.executor" -> "thread-pool-executor",
      "akka.task-dispatcher.thread-pool-executor.core-pool-size-min" -> ("%d" format concurrentDeploys),
      "akka.task-dispatcher.thread-pool-executor.core-pool-size-factor" -> ("%d" format concurrentDeploys),
      "akka.task-dispatcher.thread-pool-executor.core-pool-size-max" -> ("%d" format concurrentDeploys * 4),
      "akka.task-dispatcher.thread-pool-executor.max-pool-size-min" -> ("%d" format concurrentDeploys),
      "akka.task-dispatcher.thread-pool-executor.max-pool-size-factor" -> ("%d" format concurrentDeploys),
      "akka.task-dispatcher.thread-pool-executor.max-pool-size-max" -> ("%d" format concurrentDeploys * 4),
      "akka.task-dispatcher.throughput" -> "100"
    )
  )
  lazy val system = ActorSystem("deploy", dispatcherConfig.withFallback(ConfigFactory.load()))

  lazy val stopFlagAgent = Agent(Map.empty[UUID, String])(system.dispatcher)

  lazy val taskRunnerFactory = (context: ActorRefFactory) => context.actorOf(
    props = RoundRobinPool(
      concurrentDeploys,
      supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 minute) { case _ => Restart }
    ).props(Props(classOf[TaskRunner], stopFlagAgent).withDispatcher("akka.task-dispatcher")),
    name = "taskRunners"
  )

  lazy val deployRunnerFactory = (context: ActorRefFactory, record: Record, deployCoordinator: ActorRef, taskRunners: ActorRef) =>
    context.actorOf(
      props = Props(classOf[DeployRunner], record, deployCoordinator, taskRunners, stopFlagAgent),
      name = s"deployRunner-${record.uuid.toString}"
    )

  lazy val deployCoordinator = system.actorOf(Props(
    classOf[DeployCoordinator], taskRunnerFactory, deployRunnerFactory, concurrentDeploys, stopFlagAgent
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

object DeployRunner {
  type TaskWithAnnotation = (TaskReference, Option[PathAnnotation])
  sealed trait NextResult
  case class Tasks(tasks: List[TaskWithAnnotation]) extends NextResult
  case class FinishPath() extends NextResult
  case class FinishDeploy() extends NextResult

  sealed trait Message
  case class Start() extends Message
  case class TaskCompleted(reporter: DeployReporter, task: TaskReference) extends Message
  case class TaskFailed(reporter: DeployReporter, task: TaskReference, exception: Throwable) extends Message
  case class PreparationFailed(exception: Throwable) extends Message
  case class DeployReady(context: DeployContext) extends Message
}

case class DeployRunner(
  record: Record,
  deployCoordinator: ActorRef,
  taskRunners: ActorRef,
  stopFlagAgent: Agent[Map[UUID, String]]
) extends Actor with Logging {
  import DeployRunner._

  val rootReporter = DeployReporter.startDeployContext(DeployReporter.rootReporterFor(record.uuid, record.parameters))

  var deployContext: Option[DeployContext] = None
  var executing: Set[TaskReference] = Set.empty
  var completed: Set[TaskReference] = Set.empty
  var failed: Set[TaskReference] = Set.empty

  lazy val taskGraph = deployContext.map(_.tasks).getOrElse(Graph.empty)
  lazy val allTasks = taskGraph.nodes.toOuter.flatMap(_.taskReference)

  def predecessors(task: TaskNode): Set[TaskReference] = taskGraph.get(task).diPredecessors.flatMap(_.value.taskReference)
  def successors(task: TaskNode): List[TaskWithAnnotation] = {
    taskGraph.get(task).outgoing.toList.sortBy(_.pathStartPriority).flatMap{ edge =>
      edge.to.value.taskReference.map(_ -> edge.pathAnnotation)
    }
  }
  def isFinished: Boolean = allTasks == completed ++ failed
  def isExecuting: Boolean = executing.nonEmpty
  /* these two functions can return a number of things
      - Tasks: list of tasks with any path annotation that led to them in the graph
      - FinishPath: indicator there are no more tasks on this path
      - FinishDeploy: indicator that there are no more tasks for this deploy
      first will actually only ever return the first of these.  */
  def first: Tasks = Tasks(successors(taskGraph.start.value))
  def next(task: TaskReference): NextResult = {
    // TODO - see if this can be changed to use more built in methods from the graph library
    // if this was a last node and there is no other nodes executing then there is nothing left to do
    if (isFinished) FinishDeploy()
    // otherwise let's see what children are valid to return
    else {
      // candidates are all successors not already executing or completing
      val nextTaskCandidates = successors(task).filterNot{ case (t, _) => (completed ++ executing).contains(t)}
      // now filter for only tasks whose predecessors are all completed
      val nextTasks = nextTaskCandidates.filter { case (t, _) => (predecessors(t) -- completed).isEmpty }
      if (nextTasks.nonEmpty) {
        Tasks(nextTasks)
      } else {
        FinishPath()
      }
    }
  }
  // fail this task and all others on this path through the graph
  def failedFrom(task: TaskReference, failed: Set[TaskReference] = Set.empty): Set[TaskReference] = {
    // TODO - see if this can be changed to use more built in methods from the graph library
    if ((predecessors(task) -- failed).nonEmpty && failed.nonEmpty)
    // if the set of predecessors has elements that are not in the failed set then it has foreign incoming paths
      Set.empty
    else
    // otherwise, if there are only predecessors that we've failed then recurse
      Set(task) ++ successors(task).flatMap{case (succ, _) => failedFrom(succ, failed + task)}
  }
  protected[deployment] def markExecuting(tasks: Set[TaskReference]) = {
    executing ++= tasks
  }
  protected[deployment] def markComplete(task: TaskReference) = {
    executing -= task
    completed += task
  }
  protected[deployment] def markFailed(task: TaskReference) = {
    executing -= task
    failed ++= failedFrom(task)
  }
  private def cleanup = {
    DeployReporter.finishContext(rootReporter)
    deployCoordinator ! DeployCoordinator.CleanupDeploy(record.uuid)
    context.stop(self)
  }

  override def toString: String = {
    s"""
       |UUID: ${record.uuid.toString}
       |#Tasks: ${allTasks.size}
       |#Executing: ${executing.mkString("; ")}
       |#Completed: ${completed.size} Failed: ${failed.size}
       |#Done: ${completed.size+failed.size}
     """.stripMargin
  }

  def receive = {
    case Start() =>
      taskRunners ! TaskRunner.PrepareDeploy(record.uuid, record.parameters, rootReporter)

    case DeployReady(resolvedContext) =>
      deployContext = Some(resolvedContext)
      runTasks(rootReporter, first.tasks)

    case PreparationFailed(exception) =>
      log.debug("Preparation failed")
      cleanup

    case TaskCompleted(reporter, task) =>
      log.debug("Task completed")
      markComplete(task)
      next(task) match {
        case Tasks(taskMap) =>
          runTasks(reporter, taskMap)
        case FinishPath() =>
          if (reporter != rootReporter) DeployReporter.finishContext(reporter)
        case FinishDeploy() =>
          if (reporter != rootReporter) DeployReporter.finishContext(reporter)
          cleanup
      }

    case TaskFailed(reporter, task, exception) =>
      log.debug("Task failed")
      markFailed(task)
      if (reporter != rootReporter) DeployReporter.failContext(reporter)
      if (isExecuting) {
        log.debug("Failed during task and others still running - deferring clean up")
      } else {
        cleanup
      }

    case Terminated(actor) =>
      log.warn(s"Received terminate from ${actor.path}")
  }

  private def runTasks(currentReporter: DeployReporter, tasks: List[(TaskReference, Option[PathAnnotation])]) = {
    log.debug(s"Running next tasks: $tasks")
    honourStopFlag(currentReporter) {
      tasks.foreach { case (task, maybeAnnotation) =>
        val reporter = maybeAnnotation match {
          case Some(PathStart(name, _)) =>
            if (currentReporter != rootReporter) DeployReporter.finishContext(currentReporter)
            DeployReporter.pushContext(Info(s"Deploy path $name"), currentReporter)
          case _ => currentReporter
        }
        taskRunners ! TaskRunner.RunTask(record.uuid, task, reporter, new DateTime())
        executing += task
      }
    }
  }

  private def honourStopFlag(reporter: DeployReporter)(elseBlock: => Unit) {
    stopFlagAgent().get(record.uuid) match {
      case Some(userName) =>
        log.debug("Stop flag set")
        val stopMessage = s"Deploy has been stopped by $userName"
        if (reporter != rootReporter) DeployReporter.failContext(reporter, stopMessage, DeployStoppedException(stopMessage))
        if (!isExecuting) {
          DeployReporter.failContext(rootReporter, stopMessage, DeployStoppedException(stopMessage))
          log.debug("Cleaning up")
          cleanup
        }

      case None =>
        elseBlock
    }
  }

}

object DeployCoordinator {
  trait Message
  case class StartDeploy(record: Record) extends Message
  case class CleanupDeploy(uuid: UUID) extends Message
}

class DeployCoordinator(
  val taskRunnerFactory: ActorRefFactory => ActorRef,
  val deployRunnerFactory: (ActorRefFactory, Record, ActorRef, ActorRef) => ActorRef,
  maxDeploys: Int, stopFlagAgent: Agent[Map[UUID, String]]
) extends Actor with Logging {

  override def supervisorStrategy() = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 minute ) {
    case _ => Restart
  }

  val taskRunners = taskRunnerFactory(context)

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
      val deployRunner = deployRunnerFactory(context, record, context.self, taskRunners)
      deployRunners += (record.uuid -> (record, deployRunner))
      deployRunner ! DeployRunner.Start()

    case CleanupDeploy(uuid) =>
      cleanup(uuid)

    case Terminated(actor) =>
      log.warn(s"Received terminate from ${actor.path}")
  }
}

object TaskRunner {
  trait Message
  case class RunTask(uuid: UUID, task: TaskReference, reporter: DeployReporter, queueTime: DateTime) extends Message
  case class PrepareDeploy(uuid: UUID, parameters: DeployParameters, reporter: DeployReporter) extends Message
}

class TaskRunner(stopFlagAgent: Agent[Map[UUID, String]]) extends Actor with Logging {
  import TaskRunner._

  def receive = {
    case PrepareDeploy(uuid, parameters, deployReporter) =>
      try {
        DeployReporter.withFailureHandling(deployReporter) { implicit safeReporter =>
          import Configuration.artifact.aws._
          safeReporter.info("Reading deploy.json")
          val s3Artifact = S3Artifact(parameters.build, bucketName)
          val json = S3Artifact.withZipFallback(s3Artifact){ artifact =>
            Try(artifact.deployObject.fetchContentAsString()(client).get)
          }(client, safeReporter)
          val project = JsonReader.parse(json, s3Artifact)
          val context = parameters.toDeployContext(uuid, project, LookupSelector(), safeReporter, client)
          if (context.tasks.isEmpty)
            safeReporter.fail("No tasks were found to execute. Ensure the app(s) are in the list supported by this stage/host.")

          sender ! DeployRunner.DeployReady(context)
        }
      } catch {
        case t:Throwable =>
          log.debug("Preparing deploy failed")
          sender ! DeployRunner.PreparationFailed(t)
      }


    case RunTask(uuid, task, deployReporter, queueTime) =>
      import DeployMetricsActor._
      deployMetricsProcessor ! TaskStart(uuid, task.id, queueTime, new DateTime())
      log.debug(s"Running task ${task.id}")
      try {
        def stopFlagAsker: Boolean = {
          stopFlagAgent().contains(uuid)
        }
        deployReporter.taskContext(task.task) { taskReporter =>
          task.task.execute(taskReporter, stopFlagAsker)
        }
        log.debug("Sending completed message")
        sender ! DeployRunner.TaskCompleted(deployReporter, task)
      } catch {
        case t:Throwable =>
          log.debug("Sending failed message")
          sender ! DeployRunner.TaskFailed(deployReporter, task, t)
      } finally {
        deployMetricsProcessor ! TaskComplete(uuid, task.id, new DateTime())
      }
  }
}