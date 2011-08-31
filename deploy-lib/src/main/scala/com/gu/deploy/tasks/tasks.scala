package com.gu.deploy
package tasks

case class CopyFile(host: Host, source: String, dest: String) extends ShellTask {
  def commandLine = List("scp", "-r", source, "%s:%s" format(host.connectStr, dest))
  lazy val description = "%s -> %s:%s" format (source, host.connectStr, dest)
}

case class BlockFirewall(host: Host) extends RemoteShellTask {
  def commandLine = "/opt/deploy/support/block.sh"
}

case class RestartAndWait(host: Host, appName: String, port: String) extends RemoteShellTask {
  def commandLine = List("/opt/deploy/support/restart_and_wait.sh", appName, port)
}

case class UnblockFirewall(host: Host) extends RemoteShellTask {
  def commandLine = "/opt/deploy/support/block.sh"
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