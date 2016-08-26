package deployment

import java.io.File
import java.util.UUID

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
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
import scalax.file.Path

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
      "akka.task-dispatcher.thread-pool-executor.core-pool-size-max" -> ("%d" format concurrentDeploys * 2),
      "akka.task-dispatcher.thread-pool-executor.max-pool-size-min" -> ("%d" format concurrentDeploys),
      "akka.task-dispatcher.thread-pool-executor.max-pool-size-factor" -> ("%d" format concurrentDeploys),
      "akka.task-dispatcher.thread-pool-executor.max-pool-size-max" -> ("%d" format concurrentDeploys * 2),
      "akka.task-dispatcher.throughput" -> "100"
    )
  )
  lazy val system = ActorSystem("deploy", dispatcherConfig.withFallback(ConfigFactory.load()))

  lazy val taskRunnerFactory = { context:ActorRefFactory =>
    context.actorOf(
      props = RoundRobinPool(
        concurrentDeploys,
        supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 minute) { case _ => Restart }
      ).props(Props[TaskRunner].withDispatcher("akka.task-dispatcher")),
      name = "taskRunners"
    )
  }
  lazy val deployCoordinator = system.actorOf(Props(
    classOf[DeployCoordinator], taskRunnerFactory, concurrentDeploys
  ))

  import deployment.DeployCoordinator.{CheckStopFlag, StartDeploy, StopDeploy}

  def interruptibleDeploy(record: Record) {
    log.debug("Sending start deploy message to co-ordinator")
    deployCoordinator ! StartDeploy(record)
  }

  def stopDeploy(uuid: UUID, userName: String) {
    deployCoordinator ! StopDeploy(uuid, userName)
  }

  def getDeployStopFlag(uuid: UUID): Option[Boolean] = {
    try {
      implicit val timeout = Timeout(100 milliseconds)
      val stopFlag = deployCoordinator ? CheckStopFlag(uuid) mapTo manifest[Boolean]
      Some(Await.result(stopFlag, timeout.duration))
    } catch {
      case t:Throwable => None
    }
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

case class DeployRunState(
  record: Record,
  rootReporter: DeployReporter,
  context: Option[DeployContext] = None,
  executing: Set[TaskReference] = Set.empty,
  completed: Set[TaskReference] = Set.empty,
  failed: Set[TaskReference] = Set.empty
) {
  lazy val taskGraph = context.map(_.tasks).getOrElse(Graph.empty)
  lazy val allTasks = taskGraph.nodes.toOuter.flatMap(_.taskReference)

  def predecessors(task: TaskNode): Set[TaskReference] = taskGraph.get(task).diPredecessors.flatMap(_.value.taskReference)
  def successors(task: TaskNode): Set[TaskReference] = taskGraph.get(task).diSuccessors.flatMap(_.value.taskReference)
  def isFinished: Boolean = allTasks == completed ++ failed
  def isExecuting: Boolean = executing.nonEmpty
  def firstTasks: Set[TaskReference] = successors(taskGraph.start.value)
  def nextTasks(task: TaskReference): Option[Set[TaskReference]] = {
    // if this was a last node and there is no other nodes executing then there is nothing left to do
    if (isFinished) None
    // otherwise let's see what children are valid to return
    else {
      // candidates are all successors not already executing or completing
      val nextTaskCandidates = successors(task) -- completed -- executing
      // now filter for only tasks whose predecessors are all completed
      val nextTasks = nextTaskCandidates.filter { task => (predecessors(task) -- completed).isEmpty }
      Some(nextTasks)
    }
  }
  // fail this task and all others on this path through the graph
  def failedFrom(task: TaskReference, failed: Set[TaskReference] = Set.empty): Set[TaskReference] = {
    if ((predecessors(task) -- failed).nonEmpty && failed.nonEmpty)
    // if the set of predecessors has elements that are not in the failed set then it has foreign incoming paths
      Set.empty
    else
      // otherwise, if there are only predecessors that we've failed then recurse
      Set(task) ++ successors(task).flatMap(succ => failedFrom(succ, failed + task))
  }
  def withExecuting(tasks: Set[TaskReference]) = this.copy(executing = executing ++ tasks)
  def withCompleted(task: TaskReference) = this.copy(executing = executing - task, completed = completed + task)
  def withFailed(task: TaskReference) = this.copy(executing = executing - task, failed = failed ++ failedFrom(task))

  override def toString: String = {
    s"""
       |UUID: ${record.uuid.toString}
       |#Tasks: ${allTasks.size}
       |#Executing: ${executing.mkString("; ")}
       |#Completed: ${completed.size} Failed: ${failed.size}
       |#Done: ${completed.size+failed.size}
     """.stripMargin
  }
}

object DeployCoordinator {
  trait Message
  case class StartDeploy(record: Record) extends Message
  case class StopDeploy(uuid: UUID, userName: String) extends Message
  case class CheckStopFlag(uuid: UUID) extends Message
}

class DeployCoordinator(val runnerFactory: ActorRefFactory => ActorRef, maxDeploys: Int) extends Actor with Logging {
  import DeployCoordinator._
  import TaskRunner._

  override def supervisorStrategy() = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 minute ) {
    case _ => Restart
  }

  val runners = runnerFactory(context)

  var deployStateMap = Map.empty[UUID, DeployRunState]
  var deferredDeployQueue = ListBuffer[DeployCoordinator.Message]()
  var stopFlagMap = Map.empty[UUID, Option[String]]

  def schedulable(record: Record): Boolean = {
    deployStateMap.size < maxDeploys &&
      !deployStateMap.values.exists(state =>
        state.record.parameters.build.projectName == record.parameters.build.projectName &&
          state.record.parameters.stage == record.parameters.stage)
  }

  def ifStopFlagClear[T](state: DeployRunState)(block: => T): Option[T] = {
    stopFlagMap.contains(state.record.uuid) match {
      case true =>
        log.debug("Stop flag set")
        if (!state.isExecuting) {
          val stopMessage = "Deploy has been stopped by %s" format stopFlagMap(state.record.uuid).getOrElse("an unknown user")
          DeployReporter.failContext(state.rootReporter, stopMessage, DeployStoppedException(stopMessage))
          log.debug("Cleaning up")
          cleanup(state)
        }
        None

      case false =>
        Some(block)
    }
  }

  def receive = {
    case StartDeploy(record) if !schedulable(record) =>
      log.debug("Not schedulable, queuing")
      deferredDeployQueue += StartDeploy(record)

    case StartDeploy(record) if schedulable(record) =>
      log.debug("Scheduling deploy")
      val reporter = DeployReporter.startDeployContext(DeployReporter.rootReporterFor(record.uuid, record.parameters))
      val state = DeployRunState(record, reporter)
      ifStopFlagClear(state) {
        updateState(state)
        runners ! PrepareDeploy(record, reporter)
      }

    case StopDeploy(uuid, userName) =>
      log.debug("Processing deploy stop request")
      stopFlagMap += (uuid -> Some(userName))

    case DeployReady(record, deployContext) =>
      withUpdatedStateFor(record)(_.copy(context = Some(deployContext))) { state =>
        ifStopFlagClear(state) {
          runTasks(state, state.firstTasks)
        }
      }

    case PreparationFailed(record, exception) =>
      log.debug("Preparation failed")
      withStateFor(record)(cleanup)

    case TaskCompleted(record, task) =>
      log.debug("Task completed")
      withUpdatedStateFor(record)(_.withCompleted(task)) { state =>
        ifStopFlagClear(state) {
          // start next task
          state.nextTasks(task) match {
            case Some(nextTasks) =>
              runTasks(state, nextTasks)
            case None =>
              DeployReporter.finishContext(state.rootReporter)
              cleanup(state)
          }
        }
      }

    case TaskFailed(record, task, exception) =>
      log.debug("Task failed")
      withUpdatedStateFor(record)(_.withFailed(task)) { state =>
        // failed whilst running a task
        if (!state.isExecuting) {
          cleanup(state)
        } else {
          log.debug("Failed during task and others still running - deferring clean up")
        }
      }

    case CheckStopFlag(uuid) =>
      try {
        val stopFlag = stopFlagMap.contains(uuid)
        log.debug(s"stop flag requested for $uuid, responding with $stopFlag")
        sender ! stopFlag
      } catch {
        case e:Exception =>
          sender ! akka.actor.Status.Failure(e)
      }

    case Terminated(actor) =>
      log.warn(s"Received terminate from ${actor.path}")
  }

  private def runTasks(state: DeployRunState, tasks: Set[TaskReference]): Unit = {
    log.debug(s"Running next tasks: $tasks")
    tasks.foreach { task =>
      runners ! RunTask(state.record, task, state.rootReporter, new DateTime())
    }
    updateState(state.withExecuting(tasks))
  }

  private def updateState(state: DeployRunState): DeployRunState = {
    deployStateMap += (state.record.uuid -> state)
    state
  }

  def withStateFor[T](record: Record)(block: DeployRunState => T) = deployStateMap.get(record.uuid).map(block)

  def withUpdatedStateFor[T](record: Record)(transform: DeployRunState => DeployRunState)(block: DeployRunState => T) = {
    deployStateMap.get(record.uuid).map { state =>
      block(updateState(transform(state)))
    }
  }

  private def cleanup(state: DeployRunState) {
    log.debug("Cleaning up")
    DeployReporter.finishContext(state.rootReporter)

    deferredDeployQueue.foreach(self ! _)
    deferredDeployQueue.clear()

    deployStateMap -= state.record.uuid
    stopFlagMap -= state.record.uuid
  }

}

object TaskRunner {
  trait Message
  case class RunTask(record: Record, task: TaskReference, reporter: DeployReporter, queueTime: DateTime) extends Message
  case class TaskCompleted(record: Record, task: TaskReference) extends Message
  case class TaskFailed(record: Record, task: TaskReference, exception: Throwable) extends Message
  case class PreparationFailed(record: Record, exception: Throwable) extends Message

  case class PrepareDeploy(record: Record, reporter: DeployReporter) extends Message
  case class DeployReady(record: Record, context: DeployContext) extends Message
  case class RemoveArtifact(artifactDir: File) extends Message
}

class TaskRunner extends Actor with Logging {
  import TaskRunner._

  def receive = {
    case PrepareDeploy(record, deployReporter) =>
      try {
        DeployReporter.withFailureHandling(deployReporter) { implicit safeReporter =>
          import Configuration.artifact.aws._
          safeReporter.info("Reading deploy.json")
          val s3Artifact = S3Artifact(record.parameters.build, bucketName)
          val json = S3Artifact.withZipFallback(s3Artifact){ artifact =>
            Try(artifact.deployObject.fetchContentAsString()(client).get)
          }(client, safeReporter)
          val project = JsonReader.parse(json, s3Artifact)
          val context = record.parameters.toDeployContext(record.uuid, project, LookupSelector(), safeReporter, client)
          if (context.tasks.isEmpty)
            safeReporter.fail("No tasks were found to execute. Ensure the app(s) are in the list supported by this stage/host.")

          sender ! DeployReady(record, context)
        }
      } catch {
        case t:Throwable =>
          log.debug("Preparing deploy failed")
          sender ! PreparationFailed(record, t)
      }


    case RunTask(record, task, deployReporter, queueTime) =>
      import DeployMetricsActor._
      deployMetricsProcessor ! TaskStart(record.uuid, task.id, queueTime, new DateTime())
      log.debug(s"Running task ${task.id}")
      try {
        def stopFlagAsker: Boolean = {
          try {
            implicit val timeout = Timeout(200 milliseconds)
            val stopFlag = sender ? DeployCoordinator.CheckStopFlag(record.uuid) mapTo manifest[Boolean]
            Await.result(stopFlag, timeout.duration)
          } catch {
            // assume false if something goes wrong
            case t:Throwable => false
          }
        }
        deployReporter.taskContext(task.task) { taskReporter =>
          task.task.execute(taskReporter, stopFlagAsker)
        }
        log.debug("Sending completed message")
        sender ! TaskCompleted(record, task)
      } catch {
        case t:Throwable =>
          log.debug("Sending failed message")
          sender ! TaskFailed(record, task, t)
      } finally {
        deployMetricsProcessor ! TaskComplete(record.uuid, task.id, new DateTime())
      }

    case RemoveArtifact(artifactDir) =>
      log.debug("Delete artifact dir")
      Path(artifactDir).delete()
  }
}