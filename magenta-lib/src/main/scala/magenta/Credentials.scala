package magenta

import java.io.File
import sun.net.dns.ResolverConfiguration.Options

sealed trait Credentials
sealed trait SshCredentials {
  def keyFile: Option[File]
}
case class KeyRing(sshCredentials: SshCredentials, other: List[Credentials] = Nil) {
  lazy val s3Credentials:Option[S3Credentials] = other.filter(_.getClass == classOf[S3Credentials]).headOption.asInstanceOf[Option[S3Credentials]]

  lazy val fastlyCredentials: Option[FastlyCredentials] = {
    other.find(_.getClass == classOf[FastlyCredentials]).asInstanceOf[Option[FastlyCredentials]]
  }
}

case class S3Credentials(accessKey: String, secretAccessKey: String) extends Credentials

case class PassphraseProvided(user: String, passphrase: String, keyFile: Option[File]) extends SshCredentials
case class SystemUser(keyFile: Option[File]) extends SshCredentials

case class FastlyCredentials(serviceId: String, apiKey: String) extends Credentials
