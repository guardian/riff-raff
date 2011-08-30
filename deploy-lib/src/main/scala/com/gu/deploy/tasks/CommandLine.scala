package com.gu.deploy.tasks

import com.gu.deploy.{Host, Log}

// represents a command line to execute
//  including the ability to actually execute and become remote
case class CommandLine(commandLine: List[String]) {

  lazy val quoted = commandLine map quoteIfNeeded mkString " "
  private def quoteIfNeeded(s: String) = if (s.contains(" ")) "\"" + s + "\"" else s

  def run() {
    import sys.process._
    Log.info("Executing: " + quoted)
    commandLine.!
  }

  def on(host: Host) = CommandLine("ssh" :: host.name :: quoted :: Nil)
}

object CommandLine {
  implicit def fromStringList(c: List[String]) = CommandLine(c)
  implicit def fromString(c: String) = CommandLine(List(c))
}