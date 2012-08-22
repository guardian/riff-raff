package deployment

import magenta.json.JsonReader
import java.io.File
import magenta._
import akka.actor._
import controllers.{DeployController, Logging}
import java.util.UUID
import magenta.Stage
import akka.agent.Agent

object DeployActor {
  trait Event
  case class Resolve(uuid: UUID) extends Event
  case class Execute(uuid: UUID) extends Event

  lazy val system = ActorSystem("deploy")

  lazy val agent = Agent(Map.empty[String,UUID])(system)

  def apply(project:String, stage:Stage): ActorRef = {
    val actorName = "deploy-%s-%s" format (project.replace(" ", "_"), stage.name)
    val actor = agent().get(actorName).flatMap{ uuid =>
      val actorLookup = system.actorFor("%s-%s" format (actorName,uuid.toString))
      if (actorLookup != system.deadLetters) Some(actorLookup) else None
    }
    actor.getOrElse{
      val newUUID = UUID.randomUUID
      val newActor = system.actorOf(Props[DeployActor],"deploy-%s-%s-%s" format (project.replace(" ", "_"), stage.name, newUUID.toString))
      agent.send( _ + (actorName -> newUUID) )
      newActor
    }
  }
}

class DeployActor() extends Actor with Logging {
  import DeployActor._

  def receive = {
    case Resolve(uuid) => {
      val record = DeployController.await(uuid)
      record.loggingContext {
        record.withDownload { artifactDir =>
          resolveContext(artifactDir, record)
        }
      }
    }

    case Execute(uuid) => {
      val record = DeployController.await(uuid)
      record.loggingContext {
        record.withDownload { artifactDir =>
          val context = resolveContext(artifactDir, record)
          log.info("Executing deployContext")
          val keyRing = DeployInfoManager.keyRing(context)
          context.execute(keyRing)
        }
      }
    }
  }

  def resolveContext(artifactDir: File, record: DeployRecord): DeployContext = {
    log.info("Reading deploy.json")
    MessageBroker.info("Reading deploy.json")
    val project = JsonReader.parse(new File(artifactDir, "deploy.json"))
    val context = record.parameters.toDeployContext(project,record.deployInfo.hosts)
    context.tasks
    DeployController.updateWithContext() { record =>
      record.attachContext(context)
    }
    context
  }
}