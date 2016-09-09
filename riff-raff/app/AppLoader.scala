import play.api.{Application, ApplicationLoader, Logger}
import play.api.ApplicationLoader.Context
import lifecycle.ShutdownWhenInactive
import notification.HooksClient
import persistence.SummariseDeploysHousekeeping
import ci.{Builds, ContinuousDeployment}
import com.gu.management.play.{InternalManagementServer, InternalManagementServerImpl}
import conf.DeployMetrics
import deployment.Deployments
import utils.ScheduledAgent

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class AppLoader extends ApplicationLoader {

  override def load(context: Context): Application = {
    val components = new AppComponents(context)

    val lifecycleSingletons = Seq(
      ScheduledAgent,
      Deployments,
      DeployMetrics,
      HooksClient,
      Builds,
      SummariseDeploysHousekeeping,
      components.continuousDeployment,
      ShutdownWhenInactive
    )

    Logger.info(s"Calling init() on Lifecycle singletons: ${lifecycleSingletons.map(_.getClass.getName).mkString(", ")}")
    lifecycleSingletons.foreach(_.init())

    context.lifecycle.addStopHook(() => Future {
      lifecycleSingletons.reverse.foreach { singleton =>
        try {
          singleton.shutdown()
        } catch {
          case NonFatal(e) => Logger.error("Caught unhandled exception whilst calling shutdown() on Lifecycle singleton", e)
        }
      }
    }(ExecutionContext.global))

    // the management server takes care of shutting itself down with a lifecycle hook
    new InternalManagementServerImpl(context.lifecycle).startServer(conf.Management.applicationName, conf.Management.pages)

    components.application
  }

}
