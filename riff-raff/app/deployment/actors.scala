package deployment

import magenta.json.JsonReader
import java.io.File
import magenta._
import akka.actor._
import controllers.Logging
import akka.util.duration._
import akka.util.Timeout
import akka.actor.SupervisorStrategy.Restart
import akka.pattern.ask
import tasks.Task
import java.util.UUID
import magenta.teamcity.Artifact.build2download
import collection.mutable.ListBuffer
import akka.routing.RoundRobinRouter
import com.typesafe.config.ConfigFactory
import akka.dispatch.Await

object DeployControlActor extends Logging {
  trait Event
  case class Deploy(record: Record) extends Event

  lazy val config = ConfigFactory.load()
  lazy val system = ActorSystem("deploy", config.getConfig("riffraff").withFallback(config))

  lazy val deployController = system.actorOf(Props[DeployControlActor])
  lazy val deployCoordinator = system.actorOf(Props[DeployCoordinator])

  def deploy(record: Record){
    deployController ! Deploy(record)
  }

  import deployment.DeployCoordinator.{StopDeploy, StartDeploy}

  def interruptibleDeploy(record: Record) {
    val loggingContext = MessageBroker.startDeployContext(record.uuid, record.parameters)
    val deployMessage = MessageBroker.withContext(loggingContext) {
      val artifactDir = record.parameters.build.download()
      MessageBroker.info("Reading deploy.json")
      val project = JsonReader.parse(new File(artifactDir, "deploy.json"))
      val context = record.parameters.toDeployContext(record.uuid, project, DeployInfoManager.deployInfo)
      if (context.tasks.isEmpty)
        MessageBroker.fail("No tasks were found to execute. Ensure the app(s) are in the list supported by this stage/host.")
      val keyRing = DeployInfoManager.keyRing(context)

      StartDeploy(record, artifactDir, context, keyRing, loggingContext)
    }
    log.info("Sending start deploy mesage to co-ordinator")
    deployCoordinator ! deployMessage
  }

  def stopDeploy(uuid: UUID, userName: String) {
    deployCoordinator ! StopDeploy(uuid, userName)
  }
}

class DeployControlActor() extends Actor with Logging {
  import DeployControlActor._

  var deployActors = Map.empty[(String, String), ActorRef]

  override def supervisorStrategy() = OneForOneStrategy(maxNrOfRetries = 1000, withinTimeRange = 1 minute ) {
    case _ => Restart
  }

  def receive = {
    case Deploy(record) => {
      try {
        val project = record.parameters.build.projectName
        val stage = record.parameters.stage.name
        val actor = deployActors.get(project, stage).getOrElse {
          log.info("Created new actor for %s %s" format (project, stage))
          val newActor = context.actorOf(Props[DeployActor],"deploy-%s-%s" format (project.replace(" ", "_").replace("/","_"), stage))
          context.watch(newActor)
          deployActors += ((project,stage) -> newActor)
          newActor
        }
        actor ! DeployActor.Deploy(record)
      } catch {
        case e:Throwable => {
          log.error("Exception whilst dispatching deploy event", e)
        }
      }
    }
    case Terminated(actor) => {
      log.warn("Received terminate from %s " format actor.path)
      deployActors.find(_._2 == actor).map { case(key,value) =>
        deployActors -= key
      }
    }
  }

  override def postStop() {
    log.info("I've been stopped")
  }
}

object DeployActor {
  trait Event
  case class Deploy(record: Record) extends Event
}

class DeployActor() extends Actor with Logging {
  import DeployActor._

  override def preRestart(reason: Throwable, message: Option[Any]) {
    log.warn("Deploy actor has been restarted", reason)
  }

  def receive = {
    case Deploy(record) => {
      record.loggingContext {
        record.withDownload { artifactDir =>
          val context = resolveContext(artifactDir, record)
          record.taskType match {
            case TaskType.Preview => { }
            case TaskType.Deploy =>
              log.info("Executing deployContext")
              val keyRing = DeployInfoManager.keyRing(context)
              context.execute(keyRing)
          }
        }
      }
    }
  }

  def resolveContext(artifactDir: File, record: Record): DeployContext = {
    log.info("Reading deploy.json")
    MessageBroker.info("Reading deploy.json")
    val project = JsonReader.parse(new File(artifactDir, "deploy.json"))
    val context = record.parameters.toDeployContext(record.uuid, project, DeployInfoManager.deployInfo)
    context
  }

  override def postStop() {
    log.info("I've been stopped")
  }
}

case class UniqueTask(id: Int, task: Task)

case class DeployRunState(
  record: Record,
  artifactDir: File,
  context: DeployContext,
  keyRing: KeyRing,
  loggingContext: MessageBrokerContext,
  stopUserName: Option[String] = None,
  stopFlag: Boolean = false
) {
  lazy val taskList = context.tasks.zipWithIndex.map(t => UniqueTask(t._2, t._1))
  def firstTask = taskList.head
  def nextTask(task: UniqueTask): Option[UniqueTask] = taskList.drop(task.id+1).headOption
}

object DeployCoordinator {
  trait Message
  case class StartDeploy(record: Record, artifactDir: File, context: DeployContext, keyRing: KeyRing, loggingContext: MessageBrokerContext) extends Message
  case class StopDeploy(uuid: UUID, userName: String) extends Message
  case class CheckStopFlag(uuid: UUID) extends Message
}

class DeployCoordinator extends Actor with Logging {
  import TaskRunner._
  import DeployCoordinator._

  override def supervisorStrategy() = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 minute ) {
    case _ => Restart
  }

  val taskStrategy = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 minute) { case _ => Restart }
  val runners = context.actorOf(Props[TaskRunner].withDispatcher("task-dispatcher").withRouter(new RoundRobinRouter(8).withSupervisorStrategy(taskStrategy)))

  var deployStateMap = Map.empty[UUID, DeployRunState]
  var deferredDeployQueue = ListBuffer[DeployCoordinator.Message]()

  def schedulable(record: Record): Boolean = {
    deployStateMap.size < conf.Configuration.concurrency.maxDeploys &&
      deployStateMap.values.find(state =>
        state.record.parameters.build.projectName == record.parameters.build.projectName &&
          state.record.parameters.stage == record.parameters.stage
      ).isEmpty
  }

  protected def receive = {
    case StartDeploy(record, artifactDir, deployContext, keyRing, loggingContext) if !schedulable(record) =>
      log.info("Not schedulable, queuing")
      // queue if already in progress
      deferredDeployQueue += StartDeploy(record, artifactDir, deployContext, keyRing, loggingContext)

    case StartDeploy(record, artifactDir, deployContext, keyRing, loggingContext) if schedulable(record) =>
      log.info("Starting first task")
      val state = DeployRunState(record, artifactDir, deployContext, keyRing, loggingContext)
      deployStateMap += (record.uuid -> state)
      runners ! RunTask(state.record, state.keyRing, state.firstTask, loggingContext)

    case StopDeploy(uuid, userName) =>
      log.info("Processing deploy stop request")
      deployStateMap.get(uuid).foreach { state =>
        deployStateMap += (uuid -> state.copy(stopFlag = true, stopUserName = Some(userName)))
      }

    case TaskCompleted(record, task) =>
      log.info("Task completed")
      deployStateMap.get(record.uuid).foreach { state =>
        state.stopFlag match {
          case true =>
            log.info("Stop flag set")
            val stopMessage = "Deploy has been stopped by %s" format state.stopUserName.getOrElse("an unknown user")
            MessageBroker.failAllContexts(state.loggingContext, stopMessage, new DeployStoppedException(stopMessage))
            log.info("Cleaning up")
            cleanup(state)

          case false =>
            log.info("Stop flag clear")
            // start next task
            state.nextTask(task) match {
              case Some(nextTask) =>
                log.info("Running next task")
                runners ! RunTask(state.record, state.keyRing, state.nextTask(task).get, state.loggingContext)
              case None =>
                MessageBroker.finishContext(state.loggingContext)
                log.info("Cleaning up")
                cleanup(state)
            }
        }
      }

    case TaskFailed(record, exception) =>
      log.info("Task failed")
      deployStateMap.get(record.uuid).foreach(cleanup)

    case CheckStopFlag(uuid) =>
      try {
        val stopFlag = deployStateMap.get(uuid).map(_.stopFlag).getOrElse(false)
        sender ! stopFlag
      } catch {
        case e:Exception =>
          sender ! akka.actor.Status.Failure(e)
      }

    case Terminated(actor) =>
      log.warn("Received terminate from %s " format actor.path)
  }

  private def cleanup(state: DeployRunState) {
    sbt.IO.delete(state.artifactDir)

    deferredDeployQueue.foreach(self ! _)
    deferredDeployQueue.clear()

    deployStateMap -= state.record.uuid
  }

}

object TaskRunner {
  trait Message
  case class RunTask(record: Record, keyRing: KeyRing, task: UniqueTask, loggingContext:MessageBrokerContext) extends Message
  case class TaskCompleted(record: Record, task: UniqueTask) extends Message
  case class TaskFailed(record: Record, exception: Throwable) extends Message

  case class RemoveArtifact(artifactDir: File) extends Message
}

class TaskRunner extends Actor with Logging {
  import TaskRunner._

  protected def receive = {
    case RunTask(record, keyring, task, loggingContext) => {
      log.info("Running task %d" format task.id)
      try {
        def stopFlagAsker: Boolean = {
          implicit val timeout = Timeout(200 milliseconds)
          val stopFlag = sender ? DeployCoordinator.CheckStopFlag(record.uuid) mapTo manifest[Boolean]
          Await.result(stopFlag, timeout.duration)
        }
        MessageBroker.withContext(loggingContext) {
          MessageBroker.taskContext(task.task) {
            task.task.execute(keyring, stopFlagAsker)
          }
        }
        log.info("Sending completed message")
        sender ! TaskCompleted(record, task)
      } catch {
        case t:Throwable =>
          log.info("Sending failed message")
          sender ! TaskFailed(record, t)
      }
    }

    case RemoveArtifact(artifactDir) => {
      log.info("Delete artifact dir")
      sbt.IO.delete(artifactDir)
    }
  }
}

class DeployStoppedException(message:String) extends Exception(message)