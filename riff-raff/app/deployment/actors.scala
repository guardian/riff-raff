package deployment

import magenta.json.JsonReader
import java.io.File
import magenta._
import akka.actor._
import controllers.Logging
import akka.util.duration._
import akka.actor.SupervisorStrategy.Restart
import tasks.Task
import java.util.UUID
import magenta.teamcity.Artifact.build2download
import collection.mutable.ListBuffer
import akka.routing.RoundRobinRouter
import com.typesafe.config.ConfigFactory

//import collection.JavaConversions._

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
            case Task.Preview => { }
            case Task.Deploy =>
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
  artifactDir: Option[File] = None,
  stopFlag: Boolean = false,
  stopUserName: Option[String] = None,
  context: Option[DeployContext] = None,
  keyRing: Option[KeyRing] = None
) {
  lazy val taskList = context.map(_.tasks).getOrElse(Nil).zipWithIndex.map(t => UniqueTask(t._2, t._1))
  def firstTask = taskList.headOption
  def nextTask(task: UniqueTask): Option[UniqueTask] = taskList.drop(task.id+1).headOption
  def cleanUp() {}
  def stop() {}
}

object DeployCoordinator {
  trait Message
  case class StartDeploy(record: Record) extends Message
  case class StopDeploy(uuid: UUID, userName: String) extends Message
}

class DeployCoordinator extends Actor with Logging {
  import TaskRunner._
  import DeployCoordinator._

  override def supervisorStrategy() = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 minute ) {
    case _ => Restart
  }

  val taskStrategy = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 minute) { case _ => Restart }
  val runners = context.actorOf(Props[TaskRunner].withDispatcher("task-dispatcher").withRouter(new RoundRobinRouter().withSupervisorStrategy(taskStrategy)))

  var deployStateMap = Map.empty[UUID, DeployRunState]
  var deferredDeployQueue = ListBuffer[DeployCoordinator.Message]()

  def withState(uuid: UUID)(block: Option[DeployRunState] => Option[DeployRunState]) {
    val state = deployStateMap.get(uuid)
    val newState = block(state)
    if (newState.isDefined)
      deployStateMap + (uuid -> newState.get)
    else
      deployStateMap - uuid
  }

  def mapState(uuid: UUID)(block: DeployRunState => DeployRunState) {
    withState(uuid) { _.map(block) }
  }

  protected def receive = {
    case StartDeploy(newRecord) =>
      // queue if already in progress
      if (deployStateMap.size >= conf.Configuration.concurrency.maxDeploys ||
        deployStateMap.values.find(state =>
        state.record.parameters.build.projectName == newRecord.parameters.build.projectName &&
        state.record.parameters.stage == newRecord.parameters.stage
      ).isDefined) {
        deferredDeployQueue += StartDeploy(newRecord)
      } else {
        // set up records
        withState(newRecord.uuid) { _ => Some(DeployRunState(newRecord)) }
        // prepare artifact
        runners ! PrepareArtifact(newRecord)
      }
    case StopDeploy(uuid, userName) =>
      mapState(uuid) { _.copy(stopFlag = true, stopUserName = Some(userName)) }

    case ArtifactLocation(record, artifactDir) =>
      mapState(record.uuid) { _.copy(artifactDir=Some(artifactDir))}
      runners ! ResolveContext(record, artifactDir)

    case Context(record, deployContext) =>
      mapState(record.uuid) { previous =>
        val newState = previous.copy(context = Some(deployContext), keyRing = Some(DeployInfoManager.keyRing(deployContext)))
        if (newState.firstTask.isDefined) {
          runners ! RunTask(newState.record, newState.keyRing.get, newState.firstTask.get)
        } else {
          fail(newState, "No tasks were found to execute. Ensure the app(s) are in the list supported by this stage/host.")
          self ! TaskFailed(record)
        }
        newState
      }

    case TaskCompleted(record, task) =>
      withState(record.uuid) { stateOption =>
        val state = stateOption.get
        // mark task as done
        if (state.stopFlag) {
          record.loggingContext {
            MessageBroker.fail("Deploy has been stopped by %s" format state.stopUserName.getOrElse("an unknown user"))
          }
          cleanup(state)
          None
        } else {
          // start next task
          if (state.nextTask(task).isDefined) {
            runners ! RunTask(state.record, state.keyRing.get, state.nextTask(task).get)
            Some(state)
          } else {
            None
          }
        }
      }

    case TaskFailed(record) =>
      withState(record.uuid) { stateOption =>
        stateOption.foreach(cleanup)
        None
      }

    case Terminated(actor) => {
      log.warn("Received terminate from %s " format actor.path)
    }
  }

  private def cleanup(state: DeployRunState) {
    sbt.IO.delete(state.artifactDir)

    deferredDeployQueue.foreach(self ! _)
    deferredDeployQueue.clear()
  }

  private def fail(state: DeployRunState, message: String, e: Option[Throwable] = None) {
    state.record.loggingContext{
      MessageBroker.fail(message, e)
    }
  }
}

object TaskRunner {
  trait Message
  case class PrepareArtifact(record: Record) extends Message
  case class ArtifactLocation(record: Record, artifactDir: File) extends Message
  case class RemoveArtifact(artifactDir: File) extends Message

  case class ResolveContext(record: Record, artifactDir: File) extends Message
  case class Context(record: Record, context: DeployContext) extends Message

  case class RunTask(record: Record, keyRing: KeyRing, task: UniqueTask) extends Message
  case class TaskCompleted(record: Record, task: UniqueTask) extends Message

  case class TaskFailed(record: Record) extends Message
}

class TaskRunner extends Actor with Logging {
  import TaskRunner._

  protected def receive = {
    case PrepareArtifact(record) => {
      val artifactDir = record.loggingContext {
        record.parameters.build.download()
      }
      sender ! ArtifactLocation(record, artifactDir)
    }

    case RemoveArtifact(artifactDir) => {
      sbt.IO.delete(artifactDir)
    }

    case ResolveContext(record, artifactDir) => {
      val context = record.loggingContext {
        MessageBroker.info("Reading deploy.json")
        val project = JsonReader.parse(new File(artifactDir, "deploy.json"))
        record.parameters.toDeployContext(record.uuid, project, DeployInfoManager.deployInfo)
      }
      sender ! Context(record,context)
    }

    case RunTask(record, keyring, task) => {
      try {
        record.loggingContext {
          task.task.execute(keyring)
        }
        sender ! TaskCompleted(record, task)
      } catch {
        case t:Throwable =>
          sender ! TaskFailed(record)
      }
    }
  }
}