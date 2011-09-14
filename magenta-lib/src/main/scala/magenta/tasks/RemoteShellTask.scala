package magenta
package tasks

trait RemoteShellTask extends ShellTask {
  def host: Host
  lazy val remoteCommandLine = commandLine on host

  override def execute() { remoteCommandLine.run() }

  lazy val description = "on " + host.name
  override lazy val verbose = "$ " + remoteCommandLine.quoted
}
