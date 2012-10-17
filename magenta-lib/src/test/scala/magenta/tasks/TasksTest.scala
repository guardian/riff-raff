package magenta
package tasks

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import java.net.ServerSocket
import net.liftweb.util.TimeHelpers._
import scala.concurrent.ops._
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import java.io.{File, OutputStreamWriter}
import com.amazonaws.services.s3.model.{CannedAccessControlList, PutObjectRequest}
import scala._
import scala.Predef._
import org.mockito.Matchers.any
import magenta.Host
import com.amazonaws.services.autoscaling.model.{UpdateAutoScalingGroupRequest, SetDesiredCapacityRequest, AutoScalingGroup}
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient


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

    task.remoteCommandLine should be (CommandLine(List("ssh", "-qtt", "-o", "UserKnownHostsFile=/dev/null", "-o", "StrictHostKeyChecking=no", "some-user@some-host", "sudo /sbin/service app restart")))
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
      task.execute(fakeKeyRing)
    } should produce [FailException]
  }

  it should "connect to open port" in {
    val task = WaitForPort(Host("localhost"), "9998", 200 millis)
    spawn {
      val server = new ServerSocket(9998)
      server.accept().close()
      server.close()
    }
    MessageBroker.deployContext(parameters) { task.execute(fakeKeyRing) }
  }

  it should "connect to an open port after a short time" in {
    val task = WaitForPort(Host("localhost"), "9997", 1 seconds)
    spawn {
      Thread.sleep(600 millis)
      val server = new ServerSocket(9997)
      server.accept().close()
      server.close()
    }
    task.execute(fakeKeyRing)
  }


  "check_url task" should "fail after timeout" in {
    val task = CheckUrls(Host("localhost"), "9997",List("/"), 200 millis)
    evaluating {
      task.execute(fakeKeyRing)
    } should produce [FailException]
  }

  it should "get a 200 OK" in {
    val task = CheckUrls(Host("localhost"), "9997", List("/"), 200 millis)
    spawn {
      new TestServer().withResponse("HTTP/1.0 200 OK")
    }
    task.execute(fakeKeyRing)

  }

  it should "fail on a 404 NOT FOUND" in {
    val task = CheckUrls(Host("localhost"), "9997", List("/"), 200 millis)
    spawn {
      new TestServer().withResponse("HTTP/1.0 404 NOT FOUND")
    }
    evaluating {
      task.execute(fakeKeyRing)
    } should produce [FailException]
  }

  it should "fail on a 500 ERROR" in {
    val task = CheckUrls(Host("localhost"), "9997", List("/"), 200 millis)
    spawn {
      new TestServer().withResponse("HTTP/1.0 500 ERROR")
    }
    evaluating {
      task.execute(fakeKeyRing)
    } should produce [FailException]
  }
  
  "remote shell task" should "build a remote ssh line if no credentials" in {
    val remoteTask = new RemoteShellTask {
      def host = Host("some-host")

      def commandLine = CommandLine(List("ls", "-l"))
    }

    remoteTask.remoteCommandLine should be (CommandLine(List("ssh", "-qtt", "-o", "UserKnownHostsFile=/dev/null", "-o", "StrictHostKeyChecking=no", "some-host", "ls -l")))

    val remoteTaskWithUser = new RemoteShellTask {
      def host = Host("some-host") as "resin"

      def commandLine = CommandLine(List("ls", "-l"))
    }

    remoteTaskWithUser.remoteCommandLine should be (CommandLine(List("ssh", "-qtt", "-o", "UserKnownHostsFile=/dev/null", "-o", "StrictHostKeyChecking=no", "resin@some-host", "ls -l")))
  }
  
  it should "execute the command line" in {
    var passed = false
    val remoteTask = new RemoteShellTask {

      def host = Host("some-host")

      override def remoteCommandLine(credentials: Option[SshCredentials]) = new CommandLine(""::Nil) {
        override def run() { passed = true }
      }

      def commandLine = null
    }
    
    remoteTask.execute(fakeKeyRing)
    
    passed should be (true)
  }

  it should "use a specific public key if specified" in {
    val remoteTask = new RemoteShellTask {
      def host = Host("some-host")

      def commandLine = CommandLine(List("ls", "-l"))
    }

    remoteTask.remoteCommandLine(SystemUser(Some(new File("foo")))) should
      be (CommandLine(List("ssh", "-qtt", "-o", "UserKnownHostsFile=/dev/null", "-o", "StrictHostKeyChecking=no", "-i", "foo", "some-host", "ls -l")))
  }



  "S3Upload task" should "upload a single file to S3" in {

    val fileToUpload = new File("/foo/bar/the-jar.jar")


    val task = new S3Upload(Stage("CODE"), "bucket", fileToUpload) with StubS3

    task.execute(fakeKeyRing)
    val s3Client = task.s3client(fakeKeyRing)

    verify(s3Client).putObject(any(classOf[PutObjectRequest]))
    verifyNoMoreInteractions(s3Client)
  }

  it should "create an upload request with correct permissions" in {

    val baseDir = createTempDir()

    val task = new S3Upload(Stage("CODE"), "bucket", baseDir)

    val file = new File("/file/path")

    val request = task.putObjectRequestWithPublicRead("bucket", "foo/bar", file, Some("no-cache"))

    request.getBucketName should be ("bucket")

    request.getCannedAcl should be (CannedAccessControlList.PublicRead)

    request.getFile should be (file)

    request.getKey should be ("foo/bar")

    request.getMetadata.getCacheControl should be ("no-cache")
  }

  it should "correctly convert a file to a key" in {
    val baseDir = new File("/foo/bar/something").getParentFile
    val child = new File(baseDir, "the/file/name.txt")

    val task = new S3Upload(Stage("CODE"), "bucket", baseDir)

    task.toKey(child) should be ("CODE/bar/the/file/name.txt")
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

    task.execute(fakeKeyRing)
    val s3Client = task.s3client(fakeKeyRing)

    verify(s3Client, times(3)).putObject(any(classOf[PutObjectRequest]))

    verifyNoMoreInteractions(s3Client)
  }

  it should "specify custom remote shell for rsync if key-file specified" in {
    val task = CopyFile(Host("foo.com"), "/source", "/dest")

    val command = task.commandLine(KeyRing(SystemUser(Some(new File("key")))))

    command.quoted should be ("""rsync -e "ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -i key" -rv /source foo.com:/dest""")
  }

  it should "specify custom remote shell without keyfile if no key-file specified" in {
    val task = CopyFile(Host("foo.com"), "/source", "/dest")

    val command = task.commandLine(KeyRing(SystemUser(None)))

    command.quoted should be ("""rsync -e "ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no" -rv /source foo.com:/dest""")
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
    override lazy val envCredentials = new BasicAWSCredentials(accessKey, secretAccessKey)

    lazy val s3Client = mock[AmazonS3Client]
    override def s3client(keyRing: KeyRing) = s3Client
  }
  
  val fakeKeyRing = KeyRing(SystemUser(None))
  val parameters = DeployParameters(Deployer("tester"), Build("Project","1"), Stage("CODE"), RecipeName("baseRecipe.name"))
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