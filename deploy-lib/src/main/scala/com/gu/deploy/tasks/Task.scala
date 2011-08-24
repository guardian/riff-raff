package com.gu.deploy
package tasks

trait Task {
  def execute()
}

case class CopyFileTask(source:String,dest:String) extends Task {
  def execute() {

  }
}

case class BlockFirewallTask() extends Task {
  def execute() {

  }
}

case class RestartAndWaitTask() extends Task {
  def execute() {

  }
}

case class UnblockFirewallTask() extends Task {
  def execute() {

  }
}