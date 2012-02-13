package magenta
package tasks

import com.decodified.scalassh.PublicKeyLogin


trait ShellTask extends Task {
  def commandLine: CommandLine

  def execute(sshCredentials: Option[PublicKeyLogin] = None) { commandLine.run() }

  lazy val verbose = "$ " + commandLine.quoted
}
