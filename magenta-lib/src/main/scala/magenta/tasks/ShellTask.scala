package magenta
package tasks

trait ShellTask extends Task {
  def commandLine: CommandLine

  override def execute(context: DeployLogger, stopFlag: =>  Boolean) { commandLine.run(context) }

  lazy val verbose = "$ " + commandLine.quoted
}
