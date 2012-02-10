package magenta
package tasks

import com.decodified.scalassh.{SSH, SshLogin}

trait RemoteShellTask extends ShellTask {
  def host: Host
  lazy val remoteCommandLine = CommandLine("ssh" :: "-qtt" :: host.connectStr :: commandLine.quoted :: Nil)

  override def execute(sshCredentials: Option[SshLogin] = None) { sshCredentials match {
    case Some(credentials) => SSH(host.name, credentials) { client =>
      client.exec(commandLine.quoted)
    }
    case None => remoteCommandLine
  }}

  lazy val description = "on " + host.name
  override lazy val verbose = "$ " + remoteCommandLine.quoted
}
