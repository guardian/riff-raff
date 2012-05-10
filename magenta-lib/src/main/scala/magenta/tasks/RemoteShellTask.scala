package magenta
package tasks

import java.io.File
import com.decodified.scalassh.{SimplePasswordProducer, PublicKeyLogin, SSH}
import com.decodified.scalassh.PublicKeyLogin._


trait RemoteShellTask extends ShellTask {
  def host: Host
  def remoteCommandLine: CommandLine = remoteCommandLine(None)
  def remoteCommandLine(credentials: Option[Credentials]): CommandLine = {
    val keyFileArgs = for {
      creds <- credentials.toList
      file <- creds.keyFileLocation.toList
      arg <- "-i" :: file.getPath :: Nil
    } yield arg
    CommandLine("ssh" :: "-qtt" :: keyFileArgs ::: host.connectStr :: commandLine.quoted :: Nil)
  }

  override def execute(credentials: Credentials) { credentials.forScalaSsh match {
    case Some(publicKeyLogin) => {
      val credentialsForHost = host.connectAs match {
        case Some(username) => publicKeyLogin.copy(user = username)
        case None => publicKeyLogin
      }
      SSH(host.name, credentialsForHost) { client =>
        client.exec(commandLine.quoted)
      }
    }
    case None => remoteCommandLine(Some(credentials)).run()
  }}

  lazy val description = "on " + host.name
  override lazy val verbose = "$ " + remoteCommandLine.quoted
}

case class Credentials(
  user: Option[String] = None,
  passphrase: Option[String] = None,
  keyFileLocation: Option[File] = None
) {
  lazy val forScalaSsh: Option[PublicKeyLogin] = for {
    u <- user
    p <- passphrase
  } yield PublicKeyLogin(u, SimplePasswordProducer(p),
      (keyFileLocation map (x => List(x.getPath))) getOrElse (DefaultKeyLocations))
}

object Credentials {
  def apply(user: String, passphrase: String, keyFileLocation: Option[File]): Credentials =
    Credentials(Some(user), Some(passphrase), keyFileLocation)
}