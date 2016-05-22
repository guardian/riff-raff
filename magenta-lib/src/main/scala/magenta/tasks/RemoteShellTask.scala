package magenta
package tasks

import com.decodified.scalassh.{SimplePasswordProducer, PublicKeyLogin, SSH}
import com.decodified.scalassh.PublicKeyLogin.DefaultKeyLocations

trait RemoteShellTask extends ShellTask {
  def host: Host
  override def taskHost = Some(host)

  val noHostKeyChecking = "-o" :: "UserKnownHostsFile=/dev/null" :: "-o" :: "StrictHostKeyChecking=no" :: Nil

  def remoteCommandLine: CommandLine = remoteCommandLine(None)
  def remoteCommandLine(credentials: SshCredentials): CommandLine = remoteCommandLine(Some(credentials))

  protected def remoteCommandLine(credentials: Option[SshCredentials]): CommandLine = {
    val keyFileArgs = credentials.flatMap(_.keyFile).toList.flatMap("-i" :: _.getPath :: Nil)
    CommandLine(
      "ssh" :: "-qtt" :: noHostKeyChecking ::: keyFileArgs ::: host.connectStr :: commandLine.quoted :: Nil,
      commandLine.successCodes
    )
  }

  override def execute(logger: DeployLogger, stopFlag: =>  Boolean) { keyRing.sshCredentials match {
    case PassphraseProvided(user, pass, keyFile) =>
      val publicKeyLogin =
        PublicKeyLogin(user, SimplePasswordProducer(pass), keyFile map (_.getPath :: Nil) getOrElse DefaultKeyLocations)
      val credentialsForHost = host.connectAs match {
        case Some(username) => publicKeyLogin.copy(user = username)
        case None => publicKeyLogin
      }
      SSH(host.name, credentialsForHost)(_.exec(commandLine.quoted))
    case SystemUser(keyFile) => remoteCommandLine(keyRing.sshCredentials).run(logger)
  }}

  lazy val description = "on " + host.name
  override lazy val verbose = "$ " + remoteCommandLine.quoted
}
