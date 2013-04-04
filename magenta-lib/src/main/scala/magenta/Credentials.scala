package magenta

import java.io.File
import sun.net.dns.ResolverConfiguration.Options

sealed trait Credentials
sealed trait SshCredentials {
  def keyFile: Option[File]
}
case class KeyRing(sshCredentials: SshCredentials, other: List[Credentials] = Nil) {
  lazy val s3Credentials:Option[S3Credentials] = other.filter(_.getClass == classOf[S3Credentials]).headOption.asInstanceOf[Option[S3Credentials]]

  lazy val apiCredentials: List[ApiCredentials] = {
    other.flatMap(credential =>
      credential match {
        case api: ApiCredentials => Some(api)
        case _ => None
      })
  }
}

case class S3Credentials(accessKey: String, secretAccessKey: String) extends Credentials

case class PassphraseProvided(user: String, passphrase: String, keyFile: Option[File]) extends SshCredentials
case class SystemUser(keyFile: Option[File]) extends SshCredentials

case class ApiCredentials(service: String, id: String, secret: String) extends Credentials
