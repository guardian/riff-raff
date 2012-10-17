package deployment

import magenta.json.JsonReader
import java.io.File
import magenta._
import akka.actor._
import controllers.{DeployController, Logging}
import akka.util.duration._
import akka.actor.SupervisorStrategy.Restart

object DeployControlActor extends Logging {
  trait Event
  case class Deploy(record: DeployRecord) extends Event

  lazy val system = ActorSystem("deploy")

  lazy val deployController = system.actorOf(Props[DeployControlActor])

  def deploy(record: DeployRecord){
    deployController ! Deploy(record)
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
          val newActor = context.actorOf(Props[DeployActor],"deploy-%s-%s" format (project.replace(" ", "_"), stage))
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
  case class Deploy(record: DeployRecord) extends Event
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

  def resolveContext(artifactDir: File, record: DeployRecord): DeployContext = {
    log.info("Reading deploy.json")
    MessageBroker.info("Reading deploy.json")
    val project = JsonReader.parse(new File(artifactDir, "deploy.json"))
    val context = record.parameters.toDeployContext(project, DeployInfoManager.deployInfo.hosts)
    context
  }

  override def postStop() {
    log.info("I've been stopped")
  }
}