package magenta

import java.io.File

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
case class KeyRing(sshCredentials: SshCredentials, apiCredentials: Map[String,ApiCredentials] = Map.empty) {
  override def toString = (sshCredentials :: apiCredentials.values.toList).mkString(", ")
}

case class PassphraseProvided(user: String, passphrase: String, keyFile: Option[File]) extends SshCredentials {
  override def toString = s"$service:$user ($keyFile)"
}
case class SystemUser(keyFile: Option[File]) extends SshCredentials {
  override def toString = s"$service ($keyFile)"
}

object ApiCredentials {
  def apply(service: String, id: String, secret: String, comment: Option[String] = None): ApiCredentials =
    DefaultApiCredentials(service, id, secret, comment)
}
case class DefaultApiCredentials(service: String, id: String, secret: String, comment: Option[String]) extends ApiCredentials {
  override def toString = s"$service:$id${comment.map(c => s" ($c)").getOrElse("")}"
}