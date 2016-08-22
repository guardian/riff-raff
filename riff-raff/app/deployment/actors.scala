package deployment

import java.io.File
import java.util.UUID

import resources.LookupSelector
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
import magenta.tasks.Task
import org.joda.time.DateTime

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try
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
  lazy val deployCoordinator = system.actorOf(Props(classOf[DeployCoordinator], taskRunnerFactory))

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
  case class TaskStart(deployId: UUID, taskId: Int, queueTime: DateTime, startTime: DateTime) extends Message
  case class TaskComplete(deployId: UUID, taskId: Int, finishTime: DateTime) extends Message
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
  var runningTasks = Map.empty[(UUID, Int), DateTime]
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

case class UniqueTask(id: Int, task: Task)

case class DeployRunState(
  record: Record,
  reporter: DeployReporter,
  context: Option[DeployContext] = None
) {
  lazy val taskList = context.map(_.tasks.zipWithIndex.map(t => UniqueTask(t._2, t._1))).getOrElse(Nil)
  def firstTask = taskList.headOption
  def nextTask(task: UniqueTask): Option[UniqueTask] = taskList.drop(task.id+1).headOption
}

object DeployCoordinator {
  trait Message
  case class StartDeploy(record: Record) extends Message
  case class StopDeploy(uuid: UUID, userName: String) extends Message
  case class CheckStopFlag(uuid: UUID) extends Message
}

class DeployCoordinator(val runnerFactory: ActorRefFactory => ActorRef) extends Actor with Logging {
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
    deployStateMap.size < conf.Configuration.concurrency.maxDeploys &&
      !deployStateMap.values.exists(state =>
        state.record.parameters.build.projectName == record.parameters.build.projectName &&
          state.record.parameters.stage == record.parameters.stage)
  }

  def ifStopFlagClear[T](state: DeployRunState)(block: => T): Option[T] = {
    stopFlagMap.contains(state.record.uuid) match {
      case true =>
        log.debug("Stop flag set")
        val stopMessage = "Deploy has been stopped by %s" format stopFlagMap(state.record.uuid).getOrElse("an unknown user")
        DeployReporter.failAllContexts(state.reporter, stopMessage, DeployStoppedException(stopMessage))
        log.debug("Cleaning up")
        cleanup(state)
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
        deployStateMap += (record.uuid -> state)
        runners ! PrepareDeploy(record, reporter)
      }

    case StopDeploy(uuid, userName) =>
      log.debug("Processing deploy stop request")
      stopFlagMap += (uuid -> Some(userName))

    case DeployReady(record, deployContext) =>
      deployStateMap.get(record.uuid).foreach  { state =>
        val newState = state.copy(
          context = Some(deployContext)
        )
        deployStateMap += (record.uuid -> newState)
        ifStopFlagClear(newState) {
          newState.firstTask.foreach { task =>
            log.debug("Starting first task")
            runners ! RunTask(newState.record, task, newState.reporter, new DateTime())
          }
        }
      }

    case TaskCompleted(record, task) =>
      log.debug("Task completed")
      deployStateMap.get(record.uuid).foreach { state =>
        ifStopFlagClear(state) {
          log.debug("Stop flag clear")
          // start next task
          state.nextTask(task) match {
            case Some(nextTask) =>
              log.debug("Running next task")
              runners ! RunTask(state.record, state.nextTask(task).get, state.reporter, new DateTime())
            case None =>
              DeployReporter.finishContext(state.reporter)
              log.debug("Cleaning up")
              cleanup(state)
          }
        }
      }

    case TaskFailed(record, exception) =>
      log.debug("Task failed")
      deployStateMap.get(record.uuid).foreach(cleanup)

    case CheckStopFlag(uuid) =>
      try {
        val stopFlag = stopFlagMap.contains(uuid)
        log.debug("stop flag requested for %s, responding with %b" format (uuid, stopFlag))
        sender ! stopFlag
      } catch {
        case e:Exception =>
          sender ! akka.actor.Status.Failure(e)
      }

    case Terminated(actor) =>
      log.warn("Received terminate from %s " format actor.path)
  }

  private def cleanup(state: DeployRunState) {
    deferredDeployQueue.foreach(self ! _)
    deferredDeployQueue.clear()

    deployStateMap -= state.record.uuid
    stopFlagMap -= state.record.uuid
  }

}

object TaskRunner {
  trait Message
  case class RunTask(record: Record, task: UniqueTask, reporter: DeployReporter, queueTime: DateTime) extends Message
  case class TaskCompleted(record: Record, task: UniqueTask) extends Message
  case class TaskFailed(record: Record, exception: Throwable) extends Message

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
          sender ! TaskFailed(record, t)
      }


    case RunTask(record, task, deployReporter, queueTime) =>
      import DeployMetricsActor._
      deployMetricsProcessor ! TaskStart(record.uuid, task.id, queueTime, new DateTime())
      log.debug("Running task %d" format task.id)
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
        DeployReporter.withFailureHandling(deployReporter) { safeReporter =>
          safeReporter.taskContext(task.task) { taskReporter =>
            task.task.execute(taskReporter, stopFlagAsker)
          }
        }
        log.debug("Sending completed message")
        sender ! TaskCompleted(record, task)
      } catch {
        case t:Throwable =>
          log.debug("Sending failed message")
          sender ! TaskFailed(record, t)
      } finally {
        deployMetricsProcessor ! TaskComplete(record.uuid, task.id, new DateTime())
      }

    case RemoveArtifact(artifactDir) =>
      log.debug("Delete artifact dir")
      Path(artifactDir).delete()
  }
}