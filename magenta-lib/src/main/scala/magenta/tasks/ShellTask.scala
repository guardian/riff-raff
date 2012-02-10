package magenta
package tasks

import com.decodified.scalassh.SshLogin

trait ShellTask extends Task {
  def commandLine: CommandLine

  def execute(sshCredentials: Option[SshLogin] = None) { commandLine.run() }

  lazy val verbose = "$ " + commandLine.quoted
}
