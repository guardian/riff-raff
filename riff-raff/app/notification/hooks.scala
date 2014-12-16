package notification

import java.net.{URL, URLEncoder}
import java.util.UUID

import akka.actor.{Actor, ActorSystem, Props}
import com.mongodb.casbah.commons.MongoDBObject
import controllers.Logging
import lifecycle.LifecycleWithoutApp
import magenta.{Deploy, DeployParameters, FinishContext, _}
import org.joda.time.DateTime
import persistence.{Persistence, MongoFormat, MongoSerialisable, DeployRecordDocument}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.ws._

case class Auth(user:String, password:String, scheme:WSAuthScheme=WSAuthScheme.BASIC)

case class HookConfig(id: UUID,
                      projectName: String,
                      stage: String,
                      url: String,
                      enabled: Boolean,
                      lastEdited: DateTime,
                      user:String,
                      method: HttpMethod = GET,
                      postBody: Option[String] = None) extends Logging {

  import play.api.Play.current

  def request(record: DeployRecordDocument) = {
    val templatedUrl = new HookTemplate(url, record, urlEncode = true).Template.run().get
    authFor(templatedUrl).map(ui => WS.url(templatedUrl).withAuth(ui.user, ui.password, ui.scheme))
      .getOrElse(WS.url(templatedUrl))
  }

  def authFor(url: String): Option[Auth] = Option(new URL(url).getUserInfo).flatMap { ui =>
    ui.split(':') match {
      case Array(user, password) => Some(Auth(user, password))
      case _ => None
    }
  }

  def act(record: DeployRecordDocument) {
    if (enabled) {
      val urlRequest = request(record)
      log.info(s"Calling ${urlRequest.url}")
      (method match {
        case GET => {
          urlRequest.get()
        }
        case POST => {
          postBody.map(t =>
            urlRequest.post(new HookTemplate(t, record, urlEncode = false).Template.run().get)
          ).getOrElse (
            urlRequest.post(Map[String, Seq[String]](
              "build" -> Seq(record.parameters.buildId),
              "project" -> Seq(record.parameters.projectName),
              "stage" -> Seq(record.parameters.stage),
              "recipe" -> Seq(record.parameters.recipe),
              "hosts" -> record.parameters.hostList,
              "stacks" -> record.parameters.stacks,
              "deployer" -> Seq(record.parameters.deployer),
              "uuid" -> Seq(record.uuid.toString),
              "tags" -> record.parameters.tags.toSeq.map{ case ((k, v)) => s"$k:$v" }
            ))
          )
        }
      }).map { response =>
        log.info(s"HTTP status code ${response.status} from ${urlRequest.url}")
        log.debug(s"HTTP response body from ${urlRequest.url}: ${response.status}")
      }
    } else {
      log.info("Hook disabled")
    }
  }
}
object HookConfig extends MongoSerialisable[HookConfig] {
  def apply(projectName: String, stage: String, url: String, enabled: Boolean, updatedBy:String): HookConfig =
    HookConfig(UUID.randomUUID(), projectName, stage, url, enabled, new DateTime(), updatedBy)
  implicit val hookFormat: MongoFormat[HookConfig] = new HookMongoFormat
  private class HookMongoFormat extends MongoFormat[HookConfig] {
    def toDBO(a: HookConfig) = MongoDBObject(
      "_id" -> a.id,
      "projectName" -> a.projectName,
      "stage" -> a.stage,
      "url" -> a.url,
      "enabled" -> a.enabled,
      "lastEdited" -> a.lastEdited,
      "user" -> a.user,
      "method" -> a.method.serialised,
      "postBody" -> a.postBody
    )
    def fromDBO(dbo: MongoDBObject) = Some(HookConfig(
      dbo.as[UUID]("_id"),
      dbo.as[String]("projectName"),
      dbo.as[String]("stage"),
      dbo.as[String]("url"),
      dbo.as[Boolean]("enabled"),
      dbo.as[DateTime]("lastEdited"),
      dbo.as[String]("user"),
      dbo.getAs[String]("method") map (HttpMethod(_)) getOrElse (GET),
      dbo.getAs[String]("postBody")
    ))
  }
}

case class HookCriteria(projectName: String, stage: String)
object HookCriteria extends MongoSerialisable[HookCriteria] {
  def apply(parameters:DeployParameters): HookCriteria = HookCriteria(parameters.build.projectName, parameters.stage.name)
  implicit val criteriaFormat: MongoFormat[HookCriteria] =
    new CriteriaMongoFormat
  private class CriteriaMongoFormat extends MongoFormat[HookCriteria] {
    def toDBO(a: HookCriteria) = MongoDBObject("projectName" -> a.projectName, "stageName" -> a.stage)
    def fromDBO(dbo: MongoDBObject) = Some(HookCriteria(dbo.as[String]("projectName"), dbo.as[String]("stageName")))
  }
}

case class HookAction(url: String, enabled: Boolean) extends Logging with MongoSerialisable[HookAction]
object HookAction extends MongoSerialisable[HookAction] {
  implicit val actionFormat: MongoFormat[HookAction] = new ActionMongoFormat
  private class ActionMongoFormat extends MongoFormat[HookAction] {
    def toDBO(a: HookAction) = MongoDBObject("url" -> a.url, "enabled" -> a.enabled)
    def fromDBO(dbo: MongoDBObject) = Some(HookAction(dbo.as[String]("url"), dbo.as[Boolean]("enabled")))
  }
}

object HooksClient extends LifecycleWithoutApp with Logging {
  trait Event
  case class Finished(uuid: UUID, params: DeployParameters)

  lazy val system = ActorSystem("notify")
  val actor = try {
    Some(system.actorOf(Props[HooksClient], "hook-client"))
  } catch { case t:Throwable => None }

  def finishedBuild(uuid: UUID, parameters: DeployParameters) {
    actor.foreach(_ ! Finished(uuid, parameters))
  }

  val messageSub = MessageBroker.messages.subscribe(message => {
    message.stack.top match {
      case FinishContext(Deploy(parameters)) =>
        finishedBuild(message.context.deployId, parameters)
      case _ =>
    }
  })

  def init() { }
  def shutdown() {
    messageSub.unsubscribe()
    actor.foreach(system.stop)
  }
}

class HooksClient extends Actor with Logging {
  import notification.HooksClient._

  def receive = {
    case Finished(uuid, params) =>
      Persistence.store.getPostDeployHook(params.build.projectName, params.stage.name).foreach { config =>
        try {
          config.act(Persistence.store.readDeploy(uuid).get)
        } catch {
          case t:Throwable =>
            log.warn(s"Exception caught whilst processing post deploy hooks for $config", t)
        }
      }
  }
}

object HttpMethod {
  def apply(stringRep: String): HttpMethod = stringRep match {
    case GET.serialised => GET
    case POST.serialised => POST
    case _ => throw new IllegalArgumentException(s"Can't translate $stringRep to HTTP verb")
  }
  def all: List[HttpMethod] = List(GET, POST)
}

sealed trait HttpMethod {
  def serialised: String
  override def toString = serialised
}
case object GET extends HttpMethod {
  override val serialised = "GET"
}
case object POST extends HttpMethod {
  override val serialised = "POST"
}