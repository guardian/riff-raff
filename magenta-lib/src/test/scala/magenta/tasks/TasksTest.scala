package magenta
package tasks

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import java.net.ServerSocket
import net.liftweb.util.TimeHelpers._
import concurrent.ops._
import java.io.OutputStreamWriter


class TasksTest extends FlatSpec with ShouldMatchers {
  "block firewall task" should "use configurable path" in {
    val host = Host("some-host") as ("some-user")

    val task = BlockFirewall(host)

    task.commandLine should be (CommandLine(List("if", "[", "-f", "/opt/deploy/bin/block-load-balancer", "];", "then", "/opt/deploy/bin/block-load-balancer", ";", "fi")))
    val rootPath = CommandLocator.rootPath
    CommandLocator.rootPath = "/bluergh/xxx"

    val task2 = BlockFirewall(host)

    task2.commandLine should be (CommandLine(List("if", "[", "-f", "/bluergh/xxx/block-load-balancer", "];", "then", "/bluergh/xxx/block-load-balancer", ";", "fi")))
    CommandLocator.rootPath = rootPath

  }
  it should "support hosts with user name" in {
    val host = Host("some-host") as ("some-user")

    val task = Restart(host, "app")

    task.remoteCommandLine should be (CommandLine(List("ssh", "-qtt", "some-user@some-host", "/sbin/service app restart")))
  }

  it should "call block script on path" in {
    val host = Host("some-host") as ("some-user")

    val task = BlockFirewall(host)

    task.commandLine should be (CommandLine(List("if", "[", "-f", CommandLocator.rootPath+"/block-load-balancer", "];", "then", CommandLocator.rootPath+"/block-load-balancer", ";", "fi")))
  }

  "unblock firewall task" should "call unblock script on path" in {
    val host = Host("some-host") as ("some-user")

    val task = UnblockFirewall(host)

    task.commandLine should be (CommandLine(List("if", "[", "-f", CommandLocator.rootPath+"/unblock-load-balancer", "];", "then", CommandLocator.rootPath+"/unblock-load-balancer", ";", "fi")))
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

  it should "connect to open port" in {
    val task = WaitForPort(Host("localhost"), "9998", 200 millis)
    spawn {
      val server = new ServerSocket(9998)
      server.accept().close()
      server.close()
    }
    task.execute()
  }

  "check_url task" should "fail after timeout" in {
    val task = CheckUrl("http://localhost:9998/1", 200 millis)
    evaluating {
      task.execute()
    } should produce [RuntimeException]
  }

  it should "get a 200 OK" in {
    val task = CheckUrl("http://localhost:9997/", 200 millis)
    spawn {
      val server = new ServerSocket(9997)
      val socket = server.accept()
      val osw = new OutputStreamWriter(socket.getOutputStream)
      osw.write("HTTP/1.0 200 OK\r\n\r\n");
      osw.flush()
      socket.close()
      server.close()
    }
    task.execute()

  }

  it should "fail on a 404 NOT FOUND" in {
    val task = CheckUrl("http://localhost:9997/", 200 millis)
    spawn {
      val server = new ServerSocket(9997)
      val socket = server.accept()
      val osw = new OutputStreamWriter(socket.getOutputStream)
      osw.write("HTTP/1.0 404 NOT FOUND\r\n\r\n");
      osw.flush()
      socket.close()
      server.close()
    }
    evaluating {
    task.execute()
    } should produce [RuntimeException]
  }

  it should "fail on a 500 ERROR" in {
    val task = CheckUrl("http://localhost:9997/", 200 millis)
    spawn {
      val server = new ServerSocket(9997)
      val socket = server.accept()
      val osw = new OutputStreamWriter(socket.getOutputStream)
      osw.write("HTTP/1.0 500 ERROR\r\n\r\n");
      osw.flush()
      socket.close()
      server.close()
    }
    evaluating {
      task.execute()
    } should produce [RuntimeException]
  }
}