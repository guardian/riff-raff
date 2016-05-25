package magenta
package tasks

trait ShellTask extends Task {
  def commandLine: CommandLine

  override def execute(reporter: DeployReporter, stopFlag: => Boolean) { commandLine.run(reporter) }

  lazy val verbose = "$ " + commandLine.quoted
}
