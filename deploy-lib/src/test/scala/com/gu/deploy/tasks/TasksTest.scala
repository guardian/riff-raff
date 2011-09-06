package com.gu.deploy.tasks

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import com.gu.deploy.Host
import java.net.{Socket, ServerSocket}
import net.liftweb.util.TimeHelpers._
import concurrent.ops._

class TasksTest extends FlatSpec with ShouldMatchers {
  "block firewall task" should "use configurable path" in {
    val host = Host("some-host") as ("some-user")

    val task = BlockFirewall(host)

    task.commandLine should be (CommandLine(List("/opt/deploy/bin/deploy-block-fw.sh")))
    val rootPath = CommandLocator.rootPath
    CommandLocator.rootPath = "/bluergh/xxx"

    val task2 = BlockFirewall(host)

    task2.commandLine should be (CommandLine(List("/bluergh/xxx/deploy-block-fw.sh")))
    CommandLocator.rootPath = rootPath

  }
  "block firewall task" should "support hosts with user name" in {
    val host = Host("some-host") as ("some-user")

    val task = BlockFirewall(host)

    task.remoteCommandLine should be (CommandLine(List("bash", "-c", "ssh -qtt some-user@some-host " + CommandLocator.rootPath + "/deploy-block-fw.sh")))
  }

  "block firewall task" should "call block script on path" in {
    val host = Host("some-host") as ("some-user")

    val task = BlockFirewall(host)

    task.commandLine should be (CommandLine(List(CommandLocator.rootPath+"/deploy-block-fw.sh")))
  }

  "unblock firewall task" should "call unblock script on path" in {
    val host = Host("some-host") as ("some-user")

    val task = UnblockFirewall(host)

    task.commandLine should be (CommandLine(List(CommandLocator.rootPath+"/deploy-unblock-fw.sh")))
  }

  "restart task" should "perform service restart" in {
    val host = Host("some-host") as ("some-user")

    val task = Restart(host, "myapp")

    task.commandLine should be (CommandLine(List("/sbin/service", "myapp", "restart")))
  }

  "waitForPort task" should "fail after timeout" in {
    val task = WaitForPort(Host("localhost"), "9998", 200 millis)
    evaluating {
      task.execute()
    } should produce [RuntimeException]
  }

  "waitForPort task" should "connect to open port" in {
    val task = WaitForPort(Host("localhost"), "9998", 200 millis)
    spawn {
      val server = new ServerSocket(9998)
      server.accept().close()
      server.close()
    }
    task.execute()
  }
}