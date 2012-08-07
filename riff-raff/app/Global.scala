import controllers.{Logging, DeployLibrary}
import notification.IrcClient
import play.mvc.Http.RequestHeader
import play.mvc.Result
import play.{Application, GlobalSettings}
import utils.ScheduledAgent
import play.api.mvc.Results.InternalServerError

class Global extends GlobalSettings with Logging {
  override def onStart(app: Application) {
    // initialise message sinks
    IrcClient.init()
    DeployLibrary.init()
  }

  override def onStop(app: Application) {
    IrcClient.shutdown()
    DeployLibrary.shutdown()
    ScheduledAgent.shutdown()
  }

  override def onError(request: RequestHeader, t: Throwable) = {
    log.error("Error whilst trying to serve request", t)
    new Result() { def getWrappedResult = InternalServerError(views.html.errorPage(t)) }
  }
}
