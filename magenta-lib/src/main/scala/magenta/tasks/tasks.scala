package magenta
package tasks

import java.net.Socket
import java.io.IOException

object CommandLocator {
  var rootPath = "/opt/deploy/bin"
}

case class CopyFile(host: Host, source: String, dest: String) extends ShellTask {
  def commandLine = List("scp", "-r", source, "%s:%s" format(host.connectStr, dest))
  lazy val description = "%s -> %s:%s" format (source, host.connectStr, dest)
}

case class BlockFirewall(host: Host) extends RemoteShellTask {
  def commandLine = CommandLocator.rootPath + "/block-load-balancer"
}

case class Restart(host: Host, appName: String) extends RemoteShellTask {
  def commandLine = List("/sbin/service", appName, "restart")
}

case class UnblockFirewall(host: Host) extends RemoteShellTask {
  def commandLine = CommandLocator.rootPath + "/unblock-load-balancer"
}

case class WaitForPort(host: Host, port: String, duration: Long) extends Task {
  def description = "to %s on %s" format(host.name, port)
  def verbose = "Wail until a socket connection can be made to %s:%s" format(host.name, port)
  val MAX_CONNECTION_ATTEMPTS: Int = 10


  def execute() {
    def checkOpen(currentTry: Int) {
      if (currentTry > MAX_CONNECTION_ATTEMPTS)
        sys.error("Timed out")
      try new Socket(host.name, port.toInt).close()
      catch { case e: IOException => {
          Thread.sleep(duration/MAX_CONNECTION_ATTEMPTS)
          checkOpen(currentTry + 1)
        }
      }
    }
    checkOpen(0)
  }
}


case class SayHello(host: Host) extends Task {
  def execute() {
    Log.info("Hello to " + host.name + "!")
  }

  def description = "to " + host.name
  def verbose = fullDescription
}

case class EchoHello(host: Host) extends ShellTask {
  def commandLine = List("echo", "hello to " + host.name)
  def description = "to " + host.name
}

case class SetSwitch(host: Host, port: String, switchName: String, switchState: Boolean) extends Task {
  def execute() = {
    // Do stuff
  }

  def switchString(b: Boolean): String = {
    if (switchState) "On"
    else "Off"
  }

  def description = "set %s to %s on %s:%s" format(switchName, switchString(switchState), host, port)

  def verbose = fullDescription
}

case class LinkFile(host: Host, source: String, destination: String) extends RemoteShellTask {
  def commandLine = List("ln", "-s", source, destination)
}

case class DjangoManagmentCmd(host: Host, appDirectory: String, command: String) extends RemoteShellTask {
  def commandLine = List()
}
