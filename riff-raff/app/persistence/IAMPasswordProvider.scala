package persistence

import java.time.{Duration, Instant}

import com.amazonaws.services.rds.auth.{GetIamAuthTokenRequest, RdsIamAuthTokenGenerator}
import conf.Config
import magenta.`package`.logger


class IAMPasswordProvider(conf: Config) extends PasswordProvider {
  private val generator = RdsIamAuthTokenGenerator.builder()
    .credentials(conf.credentialsProviderChainV1())
    .region(conf.artifact.aws.regionName)
    .build()

  override def providePassword(): String = {
    if (conf.stage == "CODE" || conf.stage =="PROD") {
      logger.info(s"Fetching password for database ${conf.postgres.hostname}, stage=${conf.stage}.")
      val authTokenRequest = GetIamAuthTokenRequest.builder.hostname(conf.postgres.hostname)
        .port(5432)
        .userName(conf.postgres.user)
        .build()
      generator.getAuthToken(authTokenRequest)
    } else {
      conf.postgres.defaultPassword
    }
  }
}

class CachingPasswordProvider(passwordProvider: PasswordProvider, duration: Duration) extends PasswordProvider {

  case class CachingState(lastPassword: String, timestamp: Instant)
  var state: Option[CachingState] = None

  override def providePassword(): String = {
    if (state.isEmpty || state.exists(_.timestamp.plus(duration).isBefore(Instant.now()))) {
      state = Some(CachingState(passwordProvider.providePassword(), Instant.now()))
    }
    state.get.lastPassword
  }
}
