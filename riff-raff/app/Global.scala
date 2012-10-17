import controllers.Logging
import datastore.MongoDatastore
import lifecycle.Lifecycle
import notification.{MessageQueue, IrcClient}
import play.mvc.Http.RequestHeader
import play.mvc.Result
import play.{Application, GlobalSettings}
import play.api.mvc.Results.InternalServerError
import scala.collection.JavaConversions._
import controllers.DeployController
import teamcity.ContinuousDeployment
import utils.ScheduledAgent

class Global extends GlobalSettings with Logging {
  // list of singletons that should be lifecycled
  val lifecycleSingletons: List[Lifecycle] = List(
    DeployController,
    IrcClient,
    MessageQueue,
    MongoDatastore,
    ScheduledAgent,
    ContinuousDeployment
  )

  override def onStart(app: Application) {
    lifecycleSingletons foreach { singleton =>
      try {
        singleton.init(app)
      } catch {
        case t:Throwable => log.error("Caught unhandled exception whilst calling init() on Lifecycle singleton", t)
      }
    }
  }

  override def onStop(app: Application) {
    lifecycleSingletons foreach { singleton =>
      try {
        singleton.shutdown(app)
      } catch {
        case t:Throwable => log.error("Caught unhandled exception whilst calling shutdown() on Lifecycle singleton", t)
      }
    }
  }

  override def onError(request: RequestHeader, t: Throwable) = {
    log.error("Error whilst trying to serve request", t)
    val reportException = if (t.getCause != null) t.getCause else t
    new Result() { def getWrappedResult = InternalServerError(views.html.errorPage(reportException)) }
  }
}