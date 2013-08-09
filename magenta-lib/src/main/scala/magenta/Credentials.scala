package magenta

import java.io.File
import sun.net.dns.ResolverConfiguration.Options

sealed trait Credentials {
  def service: String
  def comment: Option[String]
}
sealed trait SshCredentials extends Credentials {
  val service = "ssh"
  def keyFile: Option[File]
  val comment = None
}
sealed trait ApiCredentials extends Credentials {
  def id: String
  def secret: String
}
case class KeyRing(sshCredentials: SshCredentials, other: List[Credentials] = Nil) {
  lazy val apiCredentials: List[ApiCredentials] = {
    other.flatMap(credential =>
      credential match {
        case api: ApiCredentials => Some(api)
        case _ => None
      })
  }
}

case class PassphraseProvided(user: String, passphrase: String, keyFile: Option[File]) extends SshCredentials
case class SystemUser(keyFile: Option[File]) extends SshCredentials

object ApiCredentials {
  def apply(service: String, id: String, secret: String, comment: Option[String] = None): ApiCredentials =
    DefaultApiCredentials(service, id, secret, comment)
}
case class DefaultApiCredentials(service: String, id: String, secret: String, comment: Option[String]) extends ApiCredentials
