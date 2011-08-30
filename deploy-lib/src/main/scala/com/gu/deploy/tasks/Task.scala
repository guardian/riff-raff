package com.gu.deploy
package tasks

trait Task {
  // execute this task (should throw on failure)
  def execute()

  // name of this task: normally no need to override this method
  def name = getClass.getSimpleName

  // end-user friendly description of this task
  // (will normally be prefixed by name before display)
  // This gets displayed a lot, so should be simple
  def description: String

  def fullDescription = name + " " + description

  // A verbose description of this task. For command line tasks,
  //  this should be the full command line to be executed
  def verbose: String
}

trait ShellTask extends Task {
  def commandLine: CommandLine

  def execute() { commandLine.run() }

  lazy val verbose = "$ " + commandLine.quoted
}

trait RemoteShellTask extends ShellTask {
  def host: Host
  lazy val remoteCommandLine = commandLine on host

  override def execute() { remoteCommandLine.run() }

  lazy val description = "on " + host.name
  override lazy val verbose = "$ " + remoteCommandLine.quoted
}

case class CopyFile(host:Host, source:String, dest:String) extends ShellTask {
  def commandLine = List("scp", "-r", source, "%s:%s" format(host.name,dest))
  lazy val description = "%s -> %s:%s" format (source, host.name, dest)
}

case class BlockFirewall(host:Host) extends RemoteShellTask {
  def commandLine = "/opt/deploy/support/block.sh"
}

case class RestartAndWait(host:Host, appName: String, port: String) extends RemoteShellTask {
  def commandLine = List("/opt/deploy/support/restart_and_wait.sh", appName, port)
}

case class UnblockFirewall(host:Host) extends RemoteShellTask {
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