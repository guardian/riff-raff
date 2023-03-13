package persistence

import java.time.{Duration, Instant}
import com.amazonaws.services.rds.auth.{GetIamAuthTokenRequest, RdsIamAuthTokenGenerator}
import conf.Config
import magenta.`package`.logger

import scala.util.{Failure, Success, Try}

class IAMPasswordProvider(conf: Config) extends PasswordProvider {
  private val generator = RdsIamAuthTokenGenerator
    .builder()
    .credentials(conf.legacyCredentialsProviderChain())
    .region(conf.artifact.aws.regionName)
    .build()

  override def providePassword(): String = {
    if (conf.stage == "CODE" || conf.stage == "PROD") {
      logger.info(
        s"Fetching password for database ${conf.postgres.hostname}, stage=${conf.stage}."
      )
      val authTokenRequest = GetIamAuthTokenRequest.builder
        .hostname(conf.postgres.hostname)
        .port(5432)
        .userName(conf.postgres.user)
        .build()

      Try(generator.getAuthToken(authTokenRequest)) match {
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
