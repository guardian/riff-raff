package com.gu.deploy
package tasks

import java.net.Socket
import java.io.IOException

case class CopyFile(host: Host, source: String, dest: String) extends ShellTask {
  def commandLine = List("scp", "-r", source, "%s:%s" format(host.connectStr, dest))
  lazy val description = "%s -> %s:%s" format (source, host.connectStr, dest)
}

case class BlockFirewall(host: Host) extends RemoteShellTask {
  def commandLine = "deploy-block-fw.sh"
}

case class Restart(host: Host, appName: String) extends RemoteShellTask {
  def commandLine = List("sudo", "/sbin/service", appName, "restart")
}

case class UnblockFirewall(host: Host) extends RemoteShellTask {
  def commandLine = "deploy-unblock-fw.sh"
}

case class WaitForPort(host: Host, port: String, duration: Long) extends Task {
  def description = "to %s on %s" format(host.name, port)
  def verbose = fullDescription

  private def isSocketOpen = {
    try {
        new Socket(host.name, port.toInt).close()
        true
      }
    catch {
      case e:IOException => false
    }

  }

  def execute() {
    for(i <- 1 to 10) {
      if (isSocketOpen) return
      Thread.sleep(duration/10)
    }
    sys.error("Timed out")
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