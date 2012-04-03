package magenta
package tasks

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import java.net.ServerSocket
import net.liftweb.util.TimeHelpers._
import concurrent.ops._
import org.scalatest.mock.MockitoSugar
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import java.io.{File, OutputStreamWriter}


class TasksTest extends FlatSpec with ShouldMatchers with MockitoSugar{
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

    task.remoteCommandLine should be (CommandLine(List("ssh", "-qtt", "some-user@some-host", "sudo /sbin/service app restart")))
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

    task.commandLine should be (CommandLine(List("sudo", "/sbin/service", "myapp", "restart")))
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

  it should "connect to an open port after a short time" in {
    val task = WaitForPort(Host("localhost"), "9997", 1 seconds)
    spawn {
      Thread.sleep(600 millis)
      val server = new ServerSocket(9997)
      server.accept().close()
      server.close()
    }
    task.execute()
  }


  "check_url task" should "fail after timeout" in {
    val task = CheckUrls(Host("localhost"), "9997",List("/"), 200 millis)
    evaluating {
      task.execute()
    } should produce [RuntimeException]
  }

  it should "get a 200 OK" in {
    val task = CheckUrls(Host("localhost"), "9997", List("/"), 200 millis)
    spawn {
      new TestServer().withResponse("HTTP/1.0 200 OK")
    }
    task.execute()

  }

  it should "fail on a 404 NOT FOUND" in {
    val task = CheckUrls(Host("localhost"), "9997", List("/"), 200 millis)
    spawn {
      new TestServer().withResponse("HTTP/1.0 404 NOT FOUND")
    }
    evaluating {
    task.execute()
    } should produce [RuntimeException]
  }

  it should "fail on a 500 ERROR" in {
    val task = CheckUrls(Host("localhost"), "9997", List("/"), 200 millis)
    spawn {
      new TestServer().withResponse("HTTP/1.0 500 ERROR")
    }
    evaluating {
      task.execute()
    } should produce [RuntimeException]
  }
  
  "remote shell task" should "build a remote ssh line if no credentials" in {
    val remoteTask = new RemoteShellTask {
      def host = Host("some-host")

      def commandLine = CommandLine(List("ls", "-l"))
    }

    remoteTask.remoteCommandLine should be (CommandLine(List("ssh", "-qtt","some-host", "ls -l")))

    val remoteTaskWithUser = new RemoteShellTask {
      def host = Host("some-host") as "resin"

      def commandLine = CommandLine(List("ls", "-l"))
    }

    remoteTaskWithUser.remoteCommandLine should be (CommandLine(List("ssh", "-qtt", "resin@some-host", "ls -l")))
  }
  
  it should "execute the command line" in {
    var passed = false
    val remoteTask = new RemoteShellTask {

      def host = Host("some-host")

      override lazy val remoteCommandLine = new CommandLine(""::Nil) {
        override def run() { passed = true }
      }

      def commandLine = null
    }
    
    remoteTask.execute()
    
    passed should be (true)
  }

  import org.mockito.Mockito._

  "S3Upload task" should "upload a single file to S3" in {

    val fileToUpload = new File("/foo/bar/the-jar.jar")

    val task = new S3Upload(Stage("CODE"), "bucket", fileToUpload) with StubS3
    task.execute()

    verify(task.s3client).putObject("bucket", "CODE/the-jar.jar", fileToUpload)
    verifyNoMoreInteractions(task.s3client)
  }

  it should "upload a directory to S3" in {

    val baseDir = createTempDir()
    val baseDirName = "CODE/%s/" format baseDir.getName
    
    val fileOne = new File(baseDir, "one.txt")
    fileOne.createNewFile()
    val fileTwo = new File(baseDir, "two.txt")
    fileTwo.createNewFile()
    val subDir = new File(baseDir, "sub")
    subDir.mkdir()
    val fileThree = new File(subDir, "three.txt")
    fileThree.createNewFile()
    
    val task = new S3Upload(Stage("CODE"), "bucket", baseDir) with StubS3 {
      override val bucket = "bucket"
    }
    task.execute()

    verify(task.s3client).putObject("bucket", baseDirName + "one.txt", fileOne)
    verify(task.s3client).putObject("bucket", baseDirName + "two.txt", fileTwo)
    verify(task.s3client).putObject("bucket", baseDirName + "sub/three.txt", fileThree)
    verifyNoMoreInteractions(task.s3client)
  }
  
  private def createTempDir() = {
    val file = File.createTempFile("foo", "bar")
    file.delete()
    file.mkdir()
    file.deleteOnExit()
    file
  }
  
  trait StubS3 extends S3 {
    override lazy val accessKey = "access"
    override lazy val secretAccessKey = "secret"
    override lazy val credentials = new BasicAWSCredentials(accessKey, secretAccessKey)

    override val s3client = mock[AmazonS3Client]
  }
  
}



class TestServer(port:Int = 9997) {
  def withResponse(response: String) {
    val server = new ServerSocket(port)
    val socket = server.accept()
    val osw = new OutputStreamWriter(socket.getOutputStream)
    osw.write("%s\r\n\r\n" format (response));
    osw.flush()
    socket.close()
    server.close()
  }
}