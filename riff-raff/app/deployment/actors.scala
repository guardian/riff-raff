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
import akka.dispatch.Await
import play.api.Play
import play.api.Play.current
import com.typesafe.config.ConfigFactory

object DeployControlActor extends Logging {
  trait Event
  case class Deploy(record: Record) extends Event

  lazy val config = Play.configuration.underlying
  lazy val system = ActorSystem("deploy", config.getConfig("riffraff").withFallback(ConfigFactory.load()))

  lazy val deployController = system.actorOf(Props[DeployControlActor])
  lazy val deployCoordinator = system.actorOf(Props[DeployCoordinator])

  def deploy(record: Record){
    deployController ! Deploy(record)
  }

  import deployment.DeployCoordinator.{StopDeploy, StartDeploy}

  def interruptibleDeploy(record: Record) {
    log.debug("Sending start deploy mesage to co-ordinator")
    deployCoordinator ! StartDeploy(record)
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
  loggingContext: MessageBrokerContext,
  artifactDir: Option[File] = None,
  context: Option[DeployContext] = None,
  keyRing: Option[KeyRing] = None
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

class DeployCoordinator extends Actor with Logging {
  import TaskRunner._
  import DeployCoordinator._

  override def supervisorStrategy() = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 minute ) {
    case _ => Restart
  }

  val taskStrategy = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 minute) { case _ => Restart }
  val runners = context.actorOf(Props[TaskRunner].withDispatcher("akka.task-dispatcher").withRouter(new RoundRobinRouter(8).withSupervisorStrategy(taskStrategy)))

  var deployStateMap = Map.empty[UUID, DeployRunState]
  var deferredDeployQueue = ListBuffer[DeployCoordinator.Message]()
  var stopFlagMap = Map.empty[UUID, Option[String]]

  def schedulable(record: Record): Boolean = {
    deployStateMap.size < conf.Configuration.concurrency.maxDeploys &&
      deployStateMap.values.find(state =>
        state.record.parameters.build.projectName == record.parameters.build.projectName &&
          state.record.parameters.stage == record.parameters.stage
      ).isEmpty
  }

  def ifStopFlagClear[T](state: DeployRunState)(block: => T): Option[T] = {
    stopFlagMap.contains(state.record.uuid) match {
      case true =>
        log.debug("Stop flag set")
        val stopMessage = "Deploy has been stopped by %s" format stopFlagMap(state.record.uuid).getOrElse("an unknown user")
        MessageBroker.failAllContexts(state.loggingContext, stopMessage, new DeployStoppedException(stopMessage))
        log.debug("Cleaning up")
        cleanup(state)
        None

      case false =>
        Some(block)
    }
  }

  protected def receive = {
    case StartDeploy(record) if !schedulable(record) =>
      log.debug("Not schedulable, queuing")
      deferredDeployQueue += StartDeploy(record)

    case StartDeploy(record) if schedulable(record) =>
      log.debug("Scheduling deploy")
      val loggingContext = MessageBroker.startDeployContext(record.uuid, record.parameters)
      val state = DeployRunState(record, loggingContext)
      ifStopFlagClear(state) {
        deployStateMap += (record.uuid -> state)
        runners ! PrepareDeploy(record, loggingContext)
      }

    case StopDeploy(uuid, userName) =>
      log.debug("Processing deploy stop request")
      stopFlagMap += (uuid -> Some(userName))

    case DeployReady(record, artifactDir, deployContext, keyRing) =>
      deployStateMap.get(record.uuid).foreach  { state =>
        val newState = state.copy(
          artifactDir = Some(artifactDir),
          context = Some(deployContext),
          keyRing = Some(keyRing)
        )
        deployStateMap += (record.uuid -> newState)
        ifStopFlagClear(newState) {
          newState.firstTask.foreach { task =>
            log.debug("Starting first task")
            runners ! RunTask(newState.record, newState.keyRing.get, task, newState.loggingContext)
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
              runners ! RunTask(state.record, state.keyRing.get, state.nextTask(task).get, state.loggingContext)
            case None =>
              MessageBroker.finishContext(state.loggingContext)
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
    try {
      sbt.IO.delete(state.artifactDir)
    } catch {
      case t:Throwable =>
        log.warn("Exception whilst trying to delete artifact directory", t)
    }

    deferredDeployQueue.foreach(self ! _)
    deferredDeployQueue.clear()

    deployStateMap -= state.record.uuid
    stopFlagMap -= state.record.uuid
  }

}

object TaskRunner {
  trait Message
  case class RunTask(record: Record, keyRing: KeyRing, task: UniqueTask, loggingContext:MessageBrokerContext) extends Message
  case class TaskCompleted(record: Record, task: UniqueTask) extends Message
  case class TaskFailed(record: Record, exception: Throwable) extends Message

  case class PrepareDeploy(record: Record, loggingContext: MessageBrokerContext) extends Message
  case class DeployReady(record: Record, artifactDir: File, context: DeployContext, keyRing: KeyRing) extends Message
  case class RemoveArtifact(artifactDir: File) extends Message
}

class TaskRunner extends Actor with Logging {
  import TaskRunner._

  protected def receive = {
    case PrepareDeploy(record, loggingContext) =>
      try {
        MessageBroker.withContext(loggingContext) {
          val artifactDir = record.parameters.build.download()
          MessageBroker.info("Reading deploy.json")
          val project = JsonReader.parse(new File(artifactDir, "deploy.json"))
          val context = record.parameters.toDeployContext(record.uuid, project, DeployInfoManager.deployInfo)
          if (context.tasks.isEmpty)
            MessageBroker.fail("No tasks were found to execute. Ensure the app(s) are in the list supported by this stage/host.")
          val keyRing = DeployInfoManager.keyRing(context)

          sender ! DeployReady(record, artifactDir, context, keyRing)
        }
      } catch {
        case t:Throwable =>
          log.debug("Preparing deploy failed")
          sender ! TaskFailed(record, t)
      }


    case RunTask(record, keyring, task, loggingContext) => {
      log.debug("Running task %d" format task.id)
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
        log.debug("Sending completed message")
        sender ! TaskCompleted(record, task)
      } catch {
        case t:Throwable =>
          log.debug("Sending failed message")
          sender ! TaskFailed(record, t)
      }
    }

    case RemoveArtifact(artifactDir) => {
      log.debug("Delete artifact dir")
      sbt.IO.delete(artifactDir)
    }
  }
}

class DeployStoppedException(message:String) extends Exception(message)