package com.gu.deploy
package tasks

trait Task {
  def execute()
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