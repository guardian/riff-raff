package deployment

import akka.actor._
import magenta.json.JsonReader
import java.io.File
import controllers.Logging
import magenta._

object DeployActor {
  trait Event
  case class Deploy(build: Int, updateActor: ActorRef, keyRing: KeyRing, recipe: String = "default") extends Event

  lazy val system = ActorSystem("deploy")

  var deployActors = Map.empty[(String, Stage), ActorRef]

  def apply(project: String, stage: Stage): ActorRef = {
    synchronized {
      deployActors.get((project, stage)).getOrElse {
        val actor = system.actorOf(Props(new DeployActor(project, stage)), "deploy-" + project + "-" + stage.name)
        deployActors += (project, stage) -> actor
        actor
      }
    }
  }
}

class DeployActor(val project: String, val stage: Stage) extends Actor with Logging {
  import DeployActor._
  import MessageBus._

  def receive = {
    case Deploy(build, updateActor, keyRing, recipe) => {
      val taskStatus = new TaskStatus()
      val deployLogger = new DeployLogger(updateActor, taskStatus)
      val teeLogger = new TeeLogger(Log.current.value, deployLogger)
      try {
        Log.current.withValue(teeLogger) {
          Log.info("Downloading artifact")
          val artifactDir = Artifact.download("frontend::article", build)
          Log.info("Reading deploy.json")
          val project = JsonReader.parse(new File(artifactDir, "deploy.json"))
          val hosts = DeployInfo.parsedDeployInfo.filter(_.stage == stage.name)
          Log.info("Resolving tasks")
          val tasks = Resolver.resolve(project, recipe, hosts, stage)

          if (tasks.isEmpty)
            sys.error("No tasks were found to execute. Ensure the app(s) '%s' are in the list supported by this stage/host:\n%s." format (Resolver.possibleApps(project, recipe), HostList.listOfHostsAsHostList(hosts).supportedApps))
          taskStatus.addTasks(tasks)
          updateActor ! Info(taskStatus)

          tasks.foreach { task =>
            taskStatus.run(task) {
              Log.context("Executing %s..." format task.fullDescription) {
                task.execute(keyRing)
              }
            }
          }
          Log.info("Done")
        }
      } catch {
        case e =>
        Log.info(e.toString)
        Log.info(e.getStackTraceString)
        deployLogger.error("Deployment aborted due to exception")
      } finally {
        updateActor ! Finished()
      }
    }
  }

}

object MessageBus {

  trait Event

  case class Info(message: LogData) extends Event
  case class HistoryBuffer() extends Event
  case class Finished() extends Event
  case class Clear() extends Event

  lazy val system = ActorSystem("deploy")

  var updateActors = Map.empty[ActorRef, ActorRef]

  def apply(deployActor: ActorRef): ActorRef = {
    synchronized {
      updateActors.get(deployActor).getOrElse {
        val actor = system.actorOf(Props[MessageBus], "update-" + deployActor.path.name)
        updateActors += deployActor -> actor
        actor
      }
    }
  }
}

class MessageBus extends Actor with Logging {
  import MessageBus._
  var messages: Seq[LogData] = Seq.empty
  var finished = false
  def receive = {
    case Info(message) => {
      messages = messages :+ message
    }
    case HistoryBuffer() => {
      sender ! DeployLog(messages, finished)
    }
    case Finished() => {
      finished=true
    }
    case Clear() => {
      messages = Seq.empty
      finished = false
    }
  }
}

