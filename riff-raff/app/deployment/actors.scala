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
import controllers.{DeployLibrary, Logging}
import sbt.IO
import magenta.teamcity.Artifact._
import java.util.UUID

object DeployActor {
  trait Event
  case class Deploy(uuid: UUID) extends Event

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
    case Deploy(uuid) => {
      val record = DeployLibrary.await(uuid)
      val parameters = record.parameters
      MessageBroker.deployContext(uuid, parameters) {
        log.info("Downloading artifact")
        MessageBroker.info("Downloading artifact")
        parameters.build.withDownload { artifactDir =>
          log.info("Reading deploy.json")
          MessageBroker.info("Reading deploy.json")
          val project = JsonReader.parse(new File(artifactDir, "deploy.json"))

          val deployContext = parameters.toDeployContext(project, DeployInfo.hostList)
          log.info("Executing deployContext")
          deployContext.execute(record.keyRing)
        }
      }
    }
  }
}