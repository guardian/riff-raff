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

case class CopyFile(source:String,dest:String) extends Task {
  def execute() {

  }
}

case class BlockFirewall() extends Task {
  def execute() {

  }
}

case class RestartAndWait() extends Task {
  def execute() {

  }
}

case class UnblockFirewall() extends Task {
  def execute() {

  }
}


case class SayHello(host: Host) extends Task {
  def execute() {
    Log.info("Hello to " + host.name + "!")
  }
}

case class EchoHello(host: Host) extends ShellTask {
  def commandLine = List("echo", "hello to " + host.name)
}