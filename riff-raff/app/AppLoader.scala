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

    components.application
  }

}
