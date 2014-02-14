package notification

import magenta._
import java.util.UUID
import magenta.FailContext
import magenta.Deploy
import magenta.FinishContext
import magenta.StartContext
import akka.actor._
import controllers.{DeployController, routes, Logging}
import conf.Configuration
import scala.Some
import magenta.DeployParameters
import deployment.TaskType
import play.api.libs.ws.WS
import play.api.libs.json._
import lifecycle.LifecycleWithoutApp
import org.joda.time.{DateTimeZone, DateTime}
import utils.Json.DefaultJodaDateWrites
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global

/*
 Send deploy events to alerta (and graphite)
 */

object Alerta extends Logging with LifecycleWithoutApp {
  trait Event
  case class Notify(event: JsValue) extends Event

  private lazy val system = ActorSystem("notify")
  val actor = try {
      Some(system.actorOf(Props[AlertaController], "alerta-controller"))
    } catch {
      case t:Throwable =>
        log.error("Couldn't start Alerta controller", t)
        None
    }

  def sendMessage(event: Event) {
    log.debug(s"Queuing alerta event $event")
    actor.foreach(_ ! event)
  }

  lazy val sink = new MessageSink {
    def message(message: MessageWrapper) {
      val uuid = message.context.deployId
      if (DeployController.get(uuid).taskType == TaskType.Deploy)
        message.stack.top match {
          case StartContext(Deploy(parameters)) =>
            sendMessage(Notify(AlertaEvent(DeployEvent.Start, uuid, parameters, message.stack.time)))
          case FailContext(Deploy(parameters)) =>
            sendMessage(Notify(AlertaEvent(DeployEvent.Fail, uuid, parameters, message.stack.time)))
          case FinishContext(Deploy(parameters)) =>
            sendMessage(Notify(AlertaEvent(DeployEvent.Complete, uuid, parameters, message.stack.time)))
          case _ =>
        }
    }
  }

  def init() {
    MessageBroker.subscribe(sink)
  }

  def shutdown() {
    MessageBroker.unsubscribe(sink)
  }
}

class AlertaController extends Actor with Logging {
  import Alerta._
  val endpoints = conf.Configuration.alerta.endpoints

  def receive = {
    case Notify(event) => {
      try {
        endpoints.foreach { ep =>
          log.debug(s"Sending alerta event $event to $ep")
          WS.url(ep).post(event).onComplete{
            case Success(response) =>
              log.debug(s"Successfully sent alerta event $event to $ep")
            case Failure(exception) =>
              log.error(s"Exception whilst dispatching alerta event $event to $ep", exception)
          }
        }
      } catch {
        case e:Throwable => log.error("Exception whilst dispatching event", e)
      }
    }
  }

  override def postStop() {
    log.info("I've been stopped")
  }
}

object DeployEvent extends Enumeration {
  val Start = Value("DeployStarted")
  val Complete = Value("DeployCompleted")
  val Fail = Value("DeployFailed")
}

object AlertaEvent {
  def apply(event:DeployEvent.Value, uuid:UUID, params:DeployParameters, timestamp: DateTime): JsValue  = {
    val environment = params.stage.name
    val project = params.build.projectName
    val build = params.build.id
    val user = params.deployer.name

    val severityMap = Map( DeployEvent.Complete -> "normal", DeployEvent.Fail -> "minor", DeployEvent.Start -> "normal")
    val adjectiveMap = Map( DeployEvent.Complete -> "completed", DeployEvent.Fail -> "failed", DeployEvent.Start -> "started")

    Json.obj(
      "origin" -> s"riffraff/${java.net.InetAddress.getLocalHost.getHostName}",
      "group" -> "Deploys",
      "severity" -> severityMap(event),
      "service" -> List(project.split(":").head),
      "tags" -> Map("release" -> build, "user" -> user),
      "text" -> s"Deploy of $project ${adjectiveMap(event)}",
      "value" -> s"Release $build",
      "event" -> event.toString,
      "environment" -> List(environment),
      "resource" -> s"$project",
      "correlatedEvents" -> DeployEvent.values.map(_.toString).toList,
      "summary" -> s"$event of $project build $build in $environment",
      "type" -> "deployAlert",
      "moreInfo" -> s"${Configuration.urls.publicPrefix}${routes.Deployment.viewUUID(uuid.toString).url}",
      "createTime" -> timestamp.toDateTime(DateTimeZone.UTC)
    )
  }
}