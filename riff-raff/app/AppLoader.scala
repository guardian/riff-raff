import conf.DeployMetrics
import lifecycle.ShutdownWhenInactive
import notification.HooksClient
import persistence.SummariseDeploysHousekeeping
import play.api.ApplicationLoader.Context
import play.api.{Application, ApplicationLoader, Logger, LoggerConfigurator}
import riffraff.RiffRaffManagementServer
import utils.ScheduledAgent

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class AppLoader extends ApplicationLoader {

  override def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }

    val components = new AppComponents(context)

    val hooksClient = new HooksClient(components.wsClient, components.executionContext)
    val shutdownWhenInactive = new ShutdownWhenInactive(components.deployments)

    // the management server takes care of shutting itself down with a lifecycle hook
    val management = new conf.Management(shutdownWhenInactive, components.deployments)
    val managementServer = new RiffRaffManagementServer(management.applicationName, management.pages, Logger("ManagementServer"))

    val lifecycleSingletons = Seq(
      ScheduledAgent,
      components.deployments,
      components.builds,
      components.targetResolver,
      DeployMetrics,
      hooksClient,
      SummariseDeploysHousekeeping,
      components.continuousDeployment,
      managementServer,
      shutdownWhenInactive
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

    components.application
  }

}
