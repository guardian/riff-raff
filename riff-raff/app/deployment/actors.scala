package deployment

import magenta.json.JsonReader
import java.io.File
import magenta._
import collection.mutable
import collection.mutable.Buffer
import akka.actor.{Actor, Props, ActorRef, ActorSystem}
import akka.dispatch.Await
import akka.util.duration._
import akka.util.Timeout
import akka.pattern.ask
import play.api.Logger
import controllers.Logging
import net.liftweb.util.ClearClearable
import java.net.URLEncoder
import java.nio.charset.Charset

object DeployActor {
  trait Event
  case class Deploy(parameters: DeployParameters, keyRing: KeyRing) extends Event

  lazy val system = ActorSystem("deploy")

  var deployActors = Map.empty[(String, Stage), ActorRef]

  def apply(project: String, stage: Stage): ActorRef = {
    synchronized {
      deployActors.get((project, stage)).getOrElse {
        val actor = system.actorOf(Props(new DeployActor(project, stage)), "deploy-%s-%s" format (project.replace(" ", "_"), stage.name))
        deployActors += (project, stage) -> actor
        actor
      }
    }
  }
}

class DeployActor(val projectName: String, val stage: Stage) extends Actor with Logging {
  import DeployActor._

  def receive = {
    case Deploy(parameters, keyRing) => {
      MessageBroker.deployContext(parameters) {
        log.info("Downloading artifact")
        MessageBroker.info("Downloading artifact")
        val artifactDir = Artifact.download(parameters.build)
        log.info("Reading deploy.json")
        MessageBroker.info("Reading deploy.json")
        val project = JsonReader.parse(new File(artifactDir, "deploy.json"))

        val deployContext = parameters.toDeployContext(project, DeployInfo.hostList)
        log.info("Executing deployContext")
        deployContext.execute(keyRing)
      }
    }
  }
}

object MessageBus extends Logging {

  def init() {}

  trait Event

  case class Clear(key: DeploymentKey) extends Event
  case class NewMessage(messageStack: MessageStack, parameters: DeploymentKey) extends Event
  case class MessageHistoryRequest(key: DeploymentKey) extends Event
  case class MessagePayload(messageStacks: List[MessageStack]) extends Event

  lazy val system = ActorSystem("deploy")

  var updateActors = Map.empty[(String,Stage), ActorRef]
  val actor = system.actorOf(Props[MessageBus], "message-broker")

  val sink = new MessageSink {
    def message(stack: MessageStack) {
      stack.deployParameters.map(_.toDeploymentKey).foreach( actor ! NewMessage(stack, _) )
    }
  }

  MessageBroker.subscribe(sink)

  def clear(key: DeploymentKey) {
    actor ! Clear(key)
  }

  def messageHistory(key: DeploymentKey): List[MessageStack] = {
    implicit val timeout = Timeout(1.seconds)
    val futureBuffer = actor ? MessageHistoryRequest(key)
    val payload = Await.result(futureBuffer, timeout.duration).asInstanceOf[MessagePayload]
    payload.messageStacks
  }

  def deployReport(key: DeploymentKey): ReportTree = {
    DeployReport(messageHistory(key), "Deployment report")
  }
}

class MessageBus() extends Actor {
  import MessageBus._

  val keyToMessages = mutable.Map.empty[DeploymentKey,Buffer[MessageStack]].withDefaultValue(Buffer[MessageStack]())

  def receive = {
    case Clear(key) => {
      keyToMessages(key) = Buffer[MessageStack]()
    }
    case NewMessage(messageStack, key) => {
      keyToMessages(key) += messageStack
    }
    case MessageHistoryRequest(key) => {
      sender ! MessagePayload(keyToMessages.get(key) map (_.toList) getOrElse(Nil))
    }
  }
}

