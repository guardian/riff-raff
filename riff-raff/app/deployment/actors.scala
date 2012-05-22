package deployment

import akka.actor._
import magenta.json.JsonReader
import java.io.File
import magenta.{ Resolver, Stage }
import play.api.libs.iteratee.{ Enumerator, PushEnumerator }
import magenta.tasks.Task
import controllers.Logging

object DeployActor {

  trait Event
  case class Deploy(build: Int, updateActor: ActorRef, recipe: String = "default") extends Event

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
    case Deploy(build, updateActor, recipe) => {
      updateActor ! Info("Downloading artifact")
      log.info("Downloading artifact")
      val artifactDir = Artifact.download("frontend::article", build)
      updateActor ! Info("Reading deploy.json")
      log.info("Reading deploy.json")
      val project = JsonReader.parse(new File(artifactDir, "deploy.json"))
      val hosts = DeployInfo.parsedDeployInfo.filter(_.stage == stage.name)
      updateActor ! Info("Resolving tasks")
      log.info("Resolving tasks")
      val tasks = Resolver.resolve(project, recipe, hosts, stage)
      log.info("Tasks " + tasks)
      updateActor ! Tasks(tasks)
      updateActor ! Finished()
    }
  }

}

object MessageBus {

  trait Event
  case class Watch() extends Event
  case class StopWatching(channel: PushEnumerator[String]) extends Event
  case class AddMessage() extends Event
  case class Info(message: String) extends Event
  case class Tasks(tasks: List[Task]) extends Event
  case class Finished() extends Event
  case class SendHistory(channel: PushEnumerator[String]) extends Event

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
  var members = Set.empty[PushEnumerator[String]]
  var messages: Seq[String] = Seq.empty[String]
  def receive = {
    case Watch() => {
      log.info("New watcher")
      // create a push enumerator
      val channel: PushEnumerator[String] = Enumerator.imperative[String]( //onComplete = () => self ! StopWatching(channel)
      )
      // return enumerator to originator
      sender ! channel
      // add enumerator to members
      members += channel
      // push all previous messages into new enumerator
      self ! SendHistory(channel)
    }
    case StopWatching(channel) => {
      members -= channel
    }
    case Info(message) => {
      log.info("Received message to send (to %d members): %s" format (members.size, message))
      val htmlMessage = "<p>" + message + "</p>"
      messages = messages :+ htmlMessage
      members.map { _.push(htmlMessage) }
    }
    case Tasks(tasks) => {
      tasks.map { task =>
        self ! Info(task.fullDescription)
      }
    }
    case SendHistory(channel) => {
      log.info("Sending watcher %d previous messages" format messages.size)
      messages.map { message =>
        channel.push(message)
      }
    }
    case Finished() => {
      members.map { member => member.close() }
      members = Set.empty
    }
  }
}

