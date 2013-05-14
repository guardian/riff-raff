package notification

import controllers.{DeployController, Logging}
import magenta._
import akka.actor.{Actor, Props, ActorSystem}
import lifecycle.LifecycleWithoutApp
import persistence.{MongoFormat, MongoSerialisable, Persistence}
import deployment.TaskType
import java.net.URL
import com.mongodb.casbah.commons.{Imports, MongoDBObject}
import magenta.FinishContext
import magenta.DeployParameters
import magenta.Deploy
import scala.Some
import play.api.libs.ws.WS
import com.ning.http.client.Realm.AuthScheme
import play.api.libs.concurrent.Execution.Implicits._


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

case class Auth(user:String, password:String, scheme:AuthScheme=AuthScheme.BASIC)

case class HookAction(url: String, enabled: Boolean) extends Logging with MongoSerialisable[HookAction] {
  lazy val request = {
    val userInfo = Option(new URL(url).getUserInfo).flatMap { ui =>
      val elements = ui.split(':')
      if (elements.length == 2)
        Some(Auth(elements(0), elements(1)))
      else
        None
    }
    userInfo.map(ui => WS.url(url).withAuth(ui.user, ui.password, ui.scheme)).getOrElse(WS.url(url))
  }

  def act() {
    if (enabled) {
      log.info("Calling %s" format url)
      request.get().map { response =>
        log.info("HTTP status code %d" format response.status)
        log.debug("HTTP response body %s" format response.body)
      }
    } else {
      log.info("Hook disabled")
    }
  }
}
object HookAction extends MongoSerialisable[HookAction] {
  implicit val actionFormat: MongoFormat[HookAction] = new ActionMongoFormat
  private class ActionMongoFormat extends MongoFormat[HookAction] {
    def toDBO(a: HookAction) = MongoDBObject("url" -> a.url, "enabled" -> a.enabled)
    def fromDBO(dbo: MongoDBObject) = Some(HookAction(dbo.as[String]("url"), dbo.as[Boolean]("enabled")))
  }
}


object HooksClient extends LifecycleWithoutApp {
  trait Event
  case class Finished(criteria: HookCriteria)

  lazy val system = ActorSystem("notify")
  val actor = try {
    Some(system.actorOf(Props[HooksClient], "hook-client"))
  } catch { case t:Throwable => None }

  def finishedBuild(parameters: DeployParameters) {
    actor.foreach(_ ! Finished(HookCriteria(parameters)))
  }

  val sink = new MessageSink {
    def message(message: MessageWrapper) {
      message.stack.top match {
        case FinishContext(Deploy(parameters)) =>
          if (DeployController.get(message.context.deployId).taskType == TaskType.Deploy)
            finishedBuild(parameters)
        case _ =>
      }
    }
  }

  def init() {
    MessageBroker.subscribe(sink)
  }

  def shutdown() {
    MessageBroker.unsubscribe(sink)
    actor.foreach(system.stop)
  }
}

class HooksClient extends Actor with Logging {
  import HooksClient._

  def receive = {
    case Finished(criteria) =>
      try {
        Persistence.store.getPostDeployHook(criteria).foreach{ _.act() }
      } catch {
        case t:Throwable =>
          log.warn("Exception caught whilst calling any post deploy hooks for %s" format criteria, t)
      }
  }
}