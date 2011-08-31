package com.gu.deploy.tasks

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import com.gu.deploy.Host._
import com.gu.deploy.Host

class RestartTaskTest  extends FlatSpec with ShouldMatchers {
  "restart task" should "perform service restart" in {
    val host = Host("some-host") as ("some-user")

    val task = Restart(host, "myapp", "8700")

    task.commandLine should be (CommandLine(List("sudo", "/sbin/service", "myapp", "restart")))
  }
}