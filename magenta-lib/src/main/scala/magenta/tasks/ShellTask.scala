package magenta
package tasks

trait ShellTask extends Task {
  def commandLine: CommandLine

  def execute(keyRing: KeyRing, stopFlag: =>  Boolean) { commandLine.run() }

  lazy val verbose = "$ " + commandLine.quoted
}
