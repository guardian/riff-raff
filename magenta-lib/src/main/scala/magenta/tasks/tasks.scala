package magenta
package tasks

import io.Source
import java.io.{FileNotFoundException, IOException}
import java.net.{ConnectException, URL, Socket}
import com.decodified.scalassh.SshLogin

object CommandLocator {
  var rootPath = "/opt/deploy/bin"
  def conditional(binary: String) = List("if", "[", "-f", rootPath+"/"+binary, "];", "then", rootPath+"/"+binary,";", "fi" )
}

case class CopyFile(host: Host, source: String, dest: String) extends ShellTask {
  def commandLine = List("rsync", "-rv", source, "%s:%s" format(host.connectStr, dest))
  lazy val description = "%s -> %s:%s" format (source, host.connectStr, dest)
}

case class BlockFirewall(host: Host) extends RemoteShellTask {
  def commandLine = CommandLocator conditional "block-load-balancer"
}

case class Restart(host: Host, appName: String) extends RemoteShellTask {
  def commandLine = List("/sbin/service", appName, "restart")
}

case class UnblockFirewall(host: Host) extends RemoteShellTask {
  def commandLine =  CommandLocator conditional "unblock-load-balancer"
}

case class WaitForPort(host: Host, port: String, duration: Long) extends Task with RepeatedPollingCheck {
  def description = "to %s on %s" format(host.name, port)
  def verbose = "Wail until a socket connection can be made to %s:%s" format(host.name, port)

  def execute(sshCredentials: Option[SshLogin] = None) {
    check { new Socket(host.name, port.toInt).close() }
  }
}

case class CheckUrls(host: Host, port: String, paths: List[String], duration: Long) extends Task with RepeatedPollingCheck {
  def description = "check [%s] on " format(paths, host)
  def verbose = "Check that [%s] returns a 200" format(paths)

  def execute(sshCredentials: Option[SshLogin] = None) {
    for (path <- paths) check { Source.fromURL("http://%s:%s%s" format (host.connectStr, port, path))  }
  }
}

trait RepeatedPollingCheck {
  def MAX_CONNECTION_ATTEMPTS: Int = 10
  def duration: Long

  def check(action: => Unit) {
    def checkAttempt(currentAttempt: Int) {
      if (currentAttempt > MAX_CONNECTION_ATTEMPTS)
        sys.error("Timed out")
      try action
      catch {
        case e: FileNotFoundException => {
          sys.error("404 Not Found")
        }
        case e: IOException => {
          Thread.sleep(duration/MAX_CONNECTION_ATTEMPTS)
          checkAttempt(currentAttempt + 1)
        }
      }
    }
    checkAttempt(0)
  }
}


case class SayHello(host: Host) extends Task {
  def execute(sshCredentials: Option[SshLogin] = None) {
    Log.info("Hello to " + host.name + "!")
  }

  def description = "to " + host.name
  def verbose = fullDescription
}

case class EchoHello(host: Host) extends ShellTask {
  def commandLine = List("echo", "hello to " + host.name)
  def description = "to " + host.name
}

case class Link(host: Host, target: String, linkName: String) extends RemoteShellTask {
  def commandLine = List("ln", "-sfn", target, linkName)
}

case class ApacheGracefulStop(host: Host) extends RemoteShellTask {
  def commandLine = List("sudo", "/usr/sbin/apachectl", "graceful-stop")
}

case class ApacheStart(host: Host) extends RemoteShellTask {
  def commandLine = List("sudo", "/usr/sbin/apachectl", "start")
}