package persistence

import java.time.{Duration, Instant}
import conf.Config
import magenta.`package`.logger
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.rds.RdsClient
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest

import scala.util.{Failure, Success, Try}

class IAMPasswordProvider(conf: Config) extends PasswordProvider {

  private val generator = RdsClient
    .builder()
    .region(Region.of(conf.artifact.aws.regionName))
    .credentialsProvider(conf.credentialsProviderChain())
    .build()
    .utilities()

  override def providePassword(): String = {
    if (conf.stage == "CODE" || conf.stage == "PROD") {
      logger.info(
        s"Fetching password for database ${conf.postgres.hostname}, stage=${conf.stage}."
      )

      val authTokenRequest = GenerateAuthenticationTokenRequest
        .builder()
        .credentialsProvider(conf.credentialsProviderChain())
        .hostname(conf.postgres.hostname)
        .port(5432)
        .username(conf.postgres.user)
        .build()

      Try(generator.generateAuthenticationToken(authTokenRequest)) match {
        case Success(value) =>
          value
        case Failure(exception) =>
          logger.error("unable to fetch db password", exception)
          throw exception
      }
    } else {
      conf.postgres.defaultPassword
    }
  }
}

class CachingPasswordProvider(
    passwordProvider: PasswordProvider,
    duration: Duration
) extends PasswordProvider {

  case class CachingState(lastPassword: String, timestamp: Instant)
  var state: Option[CachingState] = None

  override def providePassword(): String = {
    if (
      state.isEmpty || state.exists(
        _.timestamp.plus(duration).isBefore(Instant.now())
      )
    ) {
      state = Some(
        CachingState(passwordProvider.providePassword(), Instant.now())
      )
    }
    state.get.lastPassword
  }
}
