import controllers.{Logging, DeployController}
import notification.IrcClient
import play.mvc.Http.RequestHeader
import play.mvc.Result
import play.{Application, GlobalSettings}
import teamcity.{ContinuousDeployment, TeamCity}
import utils.ScheduledAgent
import play.api.mvc.Results.InternalServerError

class Global extends GlobalSettings with Logging {
  override def onStart(app: Application) {
    // initialise message sinks
    IrcClient.init()
    DeployController.init()
    ContinuousDeployment.init()
    log.info("Starting TeamCity poller on %s" format TeamCity.tcURL.toString)
  }

  override def onStop(app: Application) {
    IrcClient.shutdown()
    DeployController.shutdown()
    ContinuousDeployment.init()
    ScheduledAgent.shutdown()
  }

  override def onError(request: RequestHeader, t: Throwable) = {
    log.error("Error whilst trying to serve request", t)
    new Result() { def getWrappedResult = InternalServerError(views.html.errorPage(t)) }
  }
}
