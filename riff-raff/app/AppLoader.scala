import conf.Config
import play.api.ApplicationLoader.Context
import play.api.{Application, ApplicationLoader, Configuration, LoggerConfigurator}

class AppLoader extends ApplicationLoader {

  override def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }

    val newConf = context.initialConfiguration ++ Configuration(Config.configuration)
    val contextWithConfig = context.copy(initialConfiguration = newConf)

    val components = new AppComponents(contextWithConfig)

    components.application
  }

}
