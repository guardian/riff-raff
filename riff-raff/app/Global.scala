import collection.mutable
import conf.{PlayRequestMetrics, DeployMetrics}
import controllers.Logging
import deployment.DeployInfoManager
import lifecycle.Lifecycle
import notification._
import persistence.SummariseDeploysHousekeeping
import play.api.mvc.{SimpleResult, RequestHeader, WithFilters}
import play.api.mvc.Results.InternalServerError
import controllers.DeployController
import ci.{ReactiveDeployment, TeamCityBuilds}
import play.filters.gzip.GzipFilter
import scala.concurrent.Future
import utils.ScheduledAgent
import play.api.Application

object Global extends WithFilters(new GzipFilter() :: PlayRequestMetrics.asFilters: _*) with Logging {

  val lifecycleSingletons = mutable.Buffer[Lifecycle]()

  override def onStart(app: Application) {
    // list of singletons - note these are inside onStart() to ensure logging has fully initialised
    lifecycleSingletons ++= List(
      ScheduledAgent,
      DeployInfoManager,
      DeployController,
      IrcClient,
      Alerta,
      AWS,
      DeployMetrics,
      HooksClient,
      TeamCityBuilds,
      TeamCityBuildPinner,
      SummariseDeploysHousekeeping,
      ReactiveDeployment
    )

    log.info(s"Calling init() on Lifecycle singletons: ${lifecycleSingletons.map(_.getClass.getName).mkString(", ")}")
    lifecycleSingletons foreach { singleton =>
      try {
        singleton.init(app)
      } catch {
        case t:Throwable => log.error("Caught unhandled exception whilst calling init() on Lifecycle singleton", t)
      }
    }
  }

  override def onStop(app: Application) {
    log.info(s"Calling shutdown() on Lifecycle singletons: ${lifecycleSingletons.reverse.map(_.getClass.getName).mkString(", ")}")
    lifecycleSingletons.reverse.foreach { singleton =>
      try {
        singleton.shutdown(app)
      } catch {
        case t:Throwable => log.error("Caught unhandled exception whilst calling shutdown() on Lifecycle singleton", t)
      }
    }
  }

  override def onError(request: RequestHeader, t: Throwable): Future[SimpleResult] = {
    log.error("Error whilst trying to serve request", t)
    val reportException = if (t.getCause != null) t.getCause else t
    Future.successful(InternalServerError(views.html.errorPage(reportException)))
  }
}