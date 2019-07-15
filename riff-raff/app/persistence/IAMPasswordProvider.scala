package persistence

import java.time.{Duration, Instant}

import com.amazonaws.services.rds.auth.{GetIamAuthTokenRequest, RdsIamAuthTokenGenerator}
import conf.Config
import magenta.`package`.logger


class IAMPasswordProvider(conf: Config) extends PasswordProvider {
  override def providePassword(): String = {
    if (conf.stage == "CODE" || conf.stage =="PROD") {
      logger.info(s"Fetching password for database ${conf.postgres.hostname}, stage=${conf.stage}.")
      val generator = RdsIamAuthTokenGenerator.builder().credentials(conf.credentialsProviderChainV1()).region(conf.artifact.aws.regionName).build()
      generator.getAuthToken(GetIamAuthTokenRequest.builder.hostname(conf.postgres.hostname).port(5432).userName(conf.postgres.user).build())
    } else {
      conf.postgres.defaultPassword
    }
  }
}

class CachingPasswordProvider(passwordProvider: PasswordProvider, duration: Duration) extends PasswordProvider {

  case class CachingState(lastPassword: String, timestamp: Instant)
  var state: Option[CachingState] = None

  override def providePassword(): String = {
    if (state.exists(_.timestamp.plus(duration).isAfter(Instant.now()))) {
    } else {
      state = Some(CachingState(passwordProvider.providePassword(), Instant.now()))
    }
    state.get.lastPassword
  }
}
