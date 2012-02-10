package magenta
package tasks

// represents a command line to execute
//  including the ability to actually execute and become remote
case class CommandLine(commandLine: List[String]) {

  lazy val quoted = (commandLine) map quoteIfNeeded mkString " "
  private def quoteIfNeeded(s: String) = if (s.contains(" ")) "\"" + s + "\"" else s

  def run() {
    import sys.process._
    Log.context("$ " + quoted) {
      val returnValue = commandLine ! ProcessLogger(Log.info(_), Log.error(_))
      Log.verbose("return value " + returnValue)
      if (returnValue != 0) sys.error("Exit code %d from command: %s" format (returnValue, quoted))
    }
  }


  def on(host: Host) = CommandLine("ssh" :: "-qtt" :: host.connectStr :: quoted :: Nil)

}

object CommandLine {
  implicit def fromStringList(c: List[String]) = CommandLine(c)
  implicit def fromString(c: String) = CommandLine(List(c))
}