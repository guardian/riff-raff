import collection.mutable
import com.gu.management.play.{StatusCounters, RequestTimer}
import controllers.Logging
import deployment.DeployInfoManager
import lifecycle.Lifecycle
import notification.{HooksClient, MessageQueue, IrcClient}
import play.mvc.Http.RequestHeader
import play.mvc.Result
import play.{Application, GlobalSettings}
import play.api.mvc.Results.InternalServerError
import controllers.DeployController
import teamcity.ContinuousDeployment
import utils.ScheduledAgent
import conf.{DeployMetrics, RequestMetrics}

class Global extends GlobalSettings with Logging with RequestTimer with StatusCounters {

  import RequestMetrics._
  override val requestTimer = RequestTimingMetric
  override val okCounter = Request200s
  override val errorCounter = Request50xs
  override val notFoundCounter = Request404s
  override val redirectCounter = Request30xs

  val lifecycleSingletons = mutable.Buffer[Lifecycle]()

  override def onStart(app: Application) {
    // list of singletons - note these are inside onStart() to ensure logging has fully initialised
    lifecycleSingletons ++= List(
      DeployInfoManager,
      DeployController,
      IrcClient,
      MessageQueue,
      ScheduledAgent,
      ContinuousDeployment,
      DeployMetrics,
      HooksClient
    )

    log.info("Calling init() on Lifecycle singletons: %s" format lifecycleSingletons.map(_.getClass.getName).mkString(", "))
    lifecycleSingletons foreach { singleton =>
      try {
        singleton.init(app)
      } catch {
        case t:Throwable => log.error("Caught unhandled exception whilst calling init() on Lifecycle singleton", t)
      }
    }
  }

  override def onStop(app: Application) {
    log.info("Calling shutdown() on Lifecycle singletons: %s" format lifecycleSingletons.map(_.getClass.getName).mkString(", "))
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