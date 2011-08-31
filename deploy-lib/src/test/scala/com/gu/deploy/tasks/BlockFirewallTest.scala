package com.gu.deploy.tasks

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import com.gu.deploy.Host


class BlockFirewallTest extends FlatSpec with ShouldMatchers {
  "block firewall task" should "support hosts with user name" in {
    val host = Host("some-host") as ("some-user")

    val task = BlockFirewall(host)

    task.remoteCommandLine should be (CommandLine(List("ssh", "-q", "some-user@some-host", "/opt/deploy/support/block.sh")))
  }

}