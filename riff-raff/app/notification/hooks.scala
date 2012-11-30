package notification

import controllers.{DeployController, Logging}
import magenta._
import akka.actor.{Actor, Props, ActorSystem}
import java.util.UUID
import lifecycle.LifecycleWithoutApp
import persistence.{MongoSerialisable, Persistence}
import deployment.Task
import java.net.URL
import com.mongodb.casbah.commons.MongoDBObject
import magenta.FinishContext
import magenta.DeployParameters
import magenta.MessageStack
import magenta.Deploy
import scala.Some
import play.api.libs.ws.WS
import com.ning.http.client.Realm.AuthScheme


case class HookCriteria(projectName: String, stage: String) extends MongoSerialisable {
  lazy val dbObject = MongoDBObject("projectName" -> projectName, "stageName" -> stage)
}
object HookCriteria {
  def apply(parameters:DeployParameters): HookCriteria = HookCriteria(parameters.build.projectName, parameters.stage.name)
  def apply(dbo: MongoDBObject): HookCriteria = HookCriteria(dbo.as[String]("projectName"), dbo.as[String]("stageName"))
}

case class Auth(user:String, password:String, scheme:AuthScheme=AuthScheme.BASIC)

case class HookAction(url: String, enabled: Boolean) extends Logging with MongoSerialisable {
  lazy val dbObject = MongoDBObject("url" -> url, "enabled" -> enabled)
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
object HookAction {
  def apply(dbo: MongoDBObject): HookAction = HookAction(dbo.as[String]("url"), dbo.as[Boolean]("enabled"))
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
          if (DeployController.get(message.context.deployId).taskType == Task.Deploy)
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