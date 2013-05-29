import collection.mutable
import conf.RiffRaffRequestMeasurementMetrics
import conf.DeployMetrics
import controllers.Logging
import deployment.DeployInfoManager
import lifecycle.Lifecycle
import notification.{TeamCityBuildPinner, HooksClient, MessageQueue, IrcClient}
import persistence.SummariseDeploysHousekeeping
import play.api.mvc.{Result, RequestHeader, WithFilters}
import play.api.mvc.Results.InternalServerError
import controllers.DeployController
import ci.{TeamCityBuilds, ContinuousDeployment}
import utils.ScheduledAgent
import play.api.Application

object Global extends WithFilters(RiffRaffRequestMeasurementMetrics.asFilters: _*) with Logging {

  val lifecycleSingletons = mutable.Buffer[Lifecycle]()

  override def onStart(app: Application) {
    // list of singletons - note these are inside onStart() to ensure logging has fully initialised
    lifecycleSingletons ++= List(
      ScheduledAgent,
      DeployInfoManager,
      DeployController,
      IrcClient,
      MessageQueue,
      ContinuousDeployment,
      DeployMetrics,
      HooksClient,
      TeamCityBuilds,
      TeamCityBuildPinner,
      SummariseDeploysHousekeeping
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
    log.info("Calling shutdown() on Lifecycle singletons: %s" format lifecycleSingletons.reverse.map(_.getClass.getName).mkString(", "))
    lifecycleSingletons.reverse.foreach { singleton =>
      try {
        singleton.shutdown(app)
      } catch {
        case t:Throwable => log.error("Caught unhandled exception whilst calling shutdown() on Lifecycle singleton", t)
      }
    }
  }

  override def onError(request: RequestHeader, t: Throwable): Result = {
    log.error("Error whilst trying to serve request", t)
    val reportException = if (t.getCause != null) t.getCause else t
    InternalServerError(views.html.errorPage(reportException))
  }
}