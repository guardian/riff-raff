import java.io.File
import java.time.Duration

import com.typesafe.config.ConfigFactory
import conf.Config
import controllers.Logging
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.bridge.SLF4JBridgeHandler
import persistence.{CachingPasswordProvider, IAMPasswordProvider}
import play.api.ApplicationLoader.Context
import play.api.{
  Application,
  ApplicationLoader,
  Configuration,
  LoggerConfigurator
}

class AppLoader extends ApplicationLoader with Logging {

  override def load(context: Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }

    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
    log.info("Installed JUL log forwarding")

    // merge config from application.conf with ~/.gu/riff-raff.conf
    val userConf = ConfigFactory.parseFile(
      new File(s"${scala.util.Properties.userHome}/.gu/riff-raff.conf")
    )
    val combinedConfig = context.initialConfiguration ++ Configuration(userConf)

    // create config object (including call to RDS to get an IAM auth token for the database)
    val appConfig = new Config(
      combinedConfig.underlying,
      DateTime.now(DateTimeZone.forID("Europe/London"))
    )

    // get JDBC passwords from IAM and cache them for 12 minutes. They are normally valid for 15 minutes
    val passwordProvider = new CachingPasswordProvider(
      new IAMPasswordProvider(appConfig),
      Duration.ofMinutes(12)
    )

    // add password from RDS IAM auth to be used by play evolutions (which relies on db.default.password property)
    val configWithNewPassword = combinedConfig ++ Configuration.from(
      Map("db.default.password" -> passwordProvider.providePassword())
    )

    val contextWithUpdatedConfig =
      context.copy(initialConfiguration = configWithNewPassword)
    val components =
      new AppComponents(contextWithUpdatedConfig, appConfig, passwordProvider)

    components.application
  }

}
