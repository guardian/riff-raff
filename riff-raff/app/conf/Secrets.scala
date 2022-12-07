package conf

import controllers.Logging
import magenta.SecretProvider
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

case class CredentialKey(service: String, account: String)

class Secrets(config: Config, ssmClient: SsmClient)
    extends SecretProvider
    with Logging {
  var credentials = Map.empty[CredentialKey, String]

  override def lookup(service: String, account: String): Option[String] = {
    credentials.get(CredentialKey(service, account))
  }

  def populate(): Unit = {
    credentials = getCredentialsFromSsm
    log.info(s"Populated credentials from SSM: ${credentials.keys.toList
        .sortBy(ck => ck.service -> ck.account)}")
  }

  def getCredentialsFromSsm: Map[CredentialKey, String] = {
    val prefix = s"${config.credentials.paramPrefix.stripSuffix("/")}/"
    val request = GetParametersByPathRequest
      .builder()
      .path(prefix)
      .withDecryption(true)
      .recursive(true)
      .build()
    try {
      val response = ssmClient.getParametersByPathPaginator(request)
      response.iterator.asScala
        .flatMap { response =>
          response.parameters.asScala
        }
        .flatMap { parameter =>
          val name = parameter.name
          val value = parameter.value
          name.stripPrefix(prefix).split("/").toList match {
            case service :: account :: Nil =>
              Some(CredentialKey(service, account) -> value)
            case other =>
              log.warn(s"Unable to ingest credential at $name ($other)")
              None
          }
        }
        .toMap
    } catch {
      case NonFatal(t) =>
        log.warn(s"Unable to build credentials map from SSM", t)
        throw t
    }
  }
}
