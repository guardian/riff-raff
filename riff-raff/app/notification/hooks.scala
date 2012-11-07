package notification

// send events after a deploy is complete

/*
configuration looks like

app / stage -> URL???

Initial requirement is to trigger teamcity build:
  When "frontend::article" is deployed to CODE then call a fixed URL

  Similar config to deployinfo?? Regex?

  Make super simple for now?

  Even constrain to only doing build triggering?

  When "frontend::article" is deployed to CODE then start build btNNN
*/
import controllers.{DeployController, Logging}
import magenta._
import akka.actor.{Actor, Props, ActorSystem}
import java.util.UUID
import lifecycle.LifecycleWithoutApp
import persistence.Persistence
import deployment.Task
import java.net.{URI, HttpURLConnection, URL}
import com.mongodb.casbah.commons.MongoDBObject
import magenta.FinishContext
import magenta.DeployParameters
import magenta.MessageStack
import magenta.Deploy
import magenta.Stage
import scala.Some
import play.libs.WS

case class HookCriteria(projectName: String, stage: Stage) {
  lazy val dbObject = MongoDBObject("_id" -> MongoDBObject("projectName" -> projectName, "stageName" -> stage.name))
}
object HookCriteria {
  def apply(parameters:DeployParameters): HookCriteria = HookCriteria(parameters.build.projectName, parameters.stage)
}

case class HookAction(url: String, enabled: Boolean) extends Logging {
  lazy val dbObject = MongoDBObject("url" -> url, "enabled" -> enabled)
  def act() {
    log.info("Calling %s")
    val response = WS.url(url).get().get(5000)
    log.info("HTTP status code %d, body %s" format (response.getStatus, response.getBody))
  }
}
object HookAction {
  def apply(dbo: MongoDBObject): HookAction = {
    HookAction(dbo.as[String]("url"), dbo.as[Boolean]("enabled"))
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
    def message(uuid: UUID, stack: MessageStack) {
      stack.top match {
        case FinishContext(Deploy(parameters)) =>
          if (DeployController.get(uuid).taskType == Task.Deploy)
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