package magenta
package tasks

trait ShellTask extends Task {
  def commandLine: CommandLine

  def execute(sshCredentials: Credentials) { commandLine.run() }

  lazy val verbose = "$ " + commandLine.quoted
}
