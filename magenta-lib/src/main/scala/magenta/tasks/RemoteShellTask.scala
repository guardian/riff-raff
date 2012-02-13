package magenta
package tasks

import com.decodified.scalassh.{PublicKeyLogin, SSH}


trait RemoteShellTask extends ShellTask {
  def host: Host
  lazy val remoteCommandLine = CommandLine("ssh" :: "-qtt" :: host.connectStr :: commandLine.quoted :: Nil)

  override def execute(sshCredentials: Option[PublicKeyLogin] = None) { sshCredentials match {
    case Some(credentials) => {
      val credentialsForHost = host.connectAs match {
        case Some(username) => credentials.copy(user = username)
        case None => credentials
      }
      SSH(host.name, credentialsForHost) { client =>
        client.exec(commandLine.quoted)
      }
    }
    case None => remoteCommandLine
  }}

  lazy val description = "on " + host.name
  override lazy val verbose = "$ " + remoteCommandLine.quoted
}
