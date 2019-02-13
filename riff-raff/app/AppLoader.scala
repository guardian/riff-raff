import java.io.File

import com.typesafe.config.ConfigFactory
import play.api.ApplicationLoader.Context
import play.api.{Application, ApplicationLoader, Configuration, LoggerConfigurator}

class AppLoader extends ApplicationLoader {

  override def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }

    val userConf = ConfigFactory.parseFile(new File(s"${scala.util.Properties.userHome}/.gu/riff-raff.conf"))
    val contextWithNewConfig = context.copy(initialConfiguration = context.initialConfiguration ++ Configuration(userConf))

    val components = new AppComponents(contextWithNewConfig)

    components.application
  }

}
