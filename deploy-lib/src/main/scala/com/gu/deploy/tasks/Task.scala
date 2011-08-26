package com.gu.deploy
package tasks

trait Task {
  def execute()
}

trait ShellTask extends Task {
  def commandLine: List[String]

  def execute() {
    import sys.process._
    commandLine.!
  }

}

trait RemoteShellTask extends ShellTask {
  def host: Host

  override def execute() {
    import sys.process._
    val sshCommand: List[String] = List("ssh", host.name, commandLine mkString(" "))
    Log.info(sshCommand mkString " ")
    sshCommand.!
  }

}

case class CopyFile(host:Host, source:String,dest:String) extends Task {
  def execute() {

  }
}

case class BlockFirewall(host:Host) extends RemoteShellTask {
    def commandLine = List("/opt/deploy/support/block.sh")
}

case class RestartAndWait(host:Host, appName: String, port: String) extends RemoteShellTask {
  def commandLine = List("/opt/deploy/support/restart_and_wait.sh", appName, port)
}

case class UnblockFirewall(host:Host) extends RemoteShellTask {
  def commandLine = List("/opt/deploy/support/block.sh")
}


case class SayHello(host: Host) extends Task {
  def execute() {
    Log.info("Hello to " + host.name + "!")
  }
}

case class EchoHello(host: Host) extends ShellTask {
  def commandLine = List("echo", "hello to " + host.name)
}