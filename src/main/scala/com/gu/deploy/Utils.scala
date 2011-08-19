package com.gu.deploy

import sys.process._

object Utils {
  def executeAndCheckRemote(host: String, username: String, cmd: String) =
    executeAndCheck(List("ssh", "-l", username, "-qt", host, cmd))
//  ^^ todo try ::

  def executeAndCheck(cmd: ProcessBuilder) = {
    try {
      cmd.!!
    } catch {
      case e: Exception => sys.error("Failed to execute %s: %s" format (cmd, e.getMessage))
    }
  }

}