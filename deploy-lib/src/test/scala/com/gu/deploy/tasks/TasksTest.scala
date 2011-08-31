package com.gu.deploy.tasks

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import com.gu.deploy.Host


class TasksTest extends FlatSpec with ShouldMatchers {
  "block firewall task" should "support hosts with user name" in {
    val host = Host("some-host") as ("some-user")

    val task = BlockFirewall(host)

    task.remoteCommandLine should be (CommandLine(List("ssh", "-q", "some-user@some-host", "deploy-block-fw.sh")))
  }

  "block firewall task" should "call block script on path" in {
    val host = Host("some-host") as ("some-user")

    val task = BlockFirewall(host)

    task.commandLine should be (CommandLine(List("deploy-block-fw.sh")))
  }

  "unblock firewall task" should "call unblock script on path" in {
    val host = Host("some-host") as ("some-user")

    val task = UnblockFirewall(host)

    task.commandLine should be (CommandLine(List("deploy-unblock-fw.sh")))
  }

  "restart task" should "perform service restart" in {
    val host = Host("some-host") as ("some-user")

    val task = Restart(host, "myapp")

    task.commandLine should be (CommandLine(List("sudo", "/sbin/service", "myapp", "restart")))
  }

}