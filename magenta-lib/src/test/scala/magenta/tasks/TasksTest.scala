package magenta
package tasks

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import java.net.ServerSocket
import net.liftweb.util.TimeHelpers._
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import java.io.{File, OutputStreamWriter}
import com.amazonaws.services.s3.model.{CannedAccessControlList, PutObjectRequest}
import org.mockito.Matchers.any
import magenta.Host
import java.util.UUID
import magenta.deployment_type.PatternValue
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global


class TasksTest extends FlatSpec with ShouldMatchers with MockitoSugar{
  implicit val fakeKeyRing = KeyRing(SystemUser(None))

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
    val task = WaitForPort(Host("localhost"), 9998, 200 millis)
    evaluating {
      task.execute()
    } should produce [FailException]
  }

  it should "connect to open port" in {
    val task = WaitForPort(Host("localhost"), 9998, 200 millis)
    Future {
      val server = new ServerSocket(9998)
      server.accept().close()
      server.close()
    }
    MessageBroker.deployContext(UUID.randomUUID(), parameters) { task.execute() }
  }

  it should "connect to an open port after a short time" in {
    val task = WaitForPort(Host("localhost"), 9997, 1 seconds)
    Future {
      Thread.sleep(600 millis)
      val server = new ServerSocket(9997)
      server.accept().close()
      server.close()
    }
    task.execute()
  }


  "check_url task" should "fail after timeout" in {
    val task = CheckUrls(Host("localhost"), 9997,List("/"), 200 millis, 5)
    evaluating {
      task.execute()
    } should produce [FailException]
  }

  it should "get a 200 OK" in {
    val task = CheckUrls(Host("localhost"), 9997, List("/"), 200 millis, 5)
    Future {
      new TestServer().withResponse("HTTP/1.0 200 OK")
    }
    task.execute()

  }

  it should "fail on a 404 NOT FOUND" in {
    val task = CheckUrls(Host("localhost"), 9997, List("/"), 200 millis, 5)
    Future {
      new TestServer().withResponse("HTTP/1.0 404 NOT FOUND")
    }
    evaluating {
      task.execute()
    } should produce [FailException]
  }

  it should "fail on a 500 ERROR" in {
    val task = CheckUrls(Host("localhost"), 9997, List("/"), 200 millis, 5)
    Future {
      new TestServer().withResponse("HTTP/1.0 500 ERROR")
    }
    evaluating {
      task.execute()
    } should produce [FailException]
  }
  
  "remote shell task" should "build a remote ssh line if no credentials" in {
    val remoteTask = new RemoteShellTask {
      def host = Host("some-host")

      def commandLine = CommandLine(List("ls", "-l"))
      def keyRing = ???
    }

    remoteTask.remoteCommandLine should be (CommandLine(List("ssh", "-qtt", "-o", "UserKnownHostsFile=/dev/null", "-o", "StrictHostKeyChecking=no", "some-host", "ls -l")))

    val remoteTaskWithUser = new RemoteShellTask {
      def host = Host("some-host") as "resin"

      def commandLine = CommandLine(List("ls", "-l"))
      def keyRing = KeyRing(SystemUser(None))
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
      def keyRing = KeyRing(SystemUser(None))
    }
    
    remoteTask.execute()
    
    passed should be (true)
  }

  it should "use a specific public key if specified" in {
    val remoteTask = new RemoteShellTask {
      def host = Host("some-host")

      def commandLine = CommandLine(List("ls", "-l"))
      def keyRing = ???
    }

    remoteTask.remoteCommandLine(SystemUser(Some(new File("foo")))) should
      be (CommandLine(List("ssh", "-qtt", "-o", "UserKnownHostsFile=/dev/null", "-o", "StrictHostKeyChecking=no", "-i", "foo", "some-host", "ls -l")))
  }

  "CopyFile task" should "specify custom remote shell for rsync if key-file specified" in {
    val task = CopyFile(Host("foo.com"), "/source", "/dest")

    val command = task.commandLine(KeyRing(SystemUser(Some(new File("key")))))

    command.quoted should be ("""rsync -e "ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -i key" -rpv /source foo.com:/dest""")
  }

  it should "specify custom remote shell without keyfile if no key-file specified" in {
    val task = CopyFile(Host("foo.com"), "/source", "/dest")

    val command = task.commandLine(KeyRing(SystemUser(None)))

    command.quoted should be ("""rsync -e "ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no" -rpv /source foo.com:/dest""")
  }

  it should "honour additive mode" in {
    val task = CopyFile(Host("foo.com"), "/source", "/dest", CopyFile.ADDITIVE_MODE)

    val command = task.commandLine(KeyRing(SystemUser(None)))

    command.quoted should be ("""rsync -e "ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no" -rpv /source foo.com:/dest""")
  }

  it should "honour mirror mode" in {
    val task = CopyFile(Host("foo.com"), "/source", "/dest", CopyFile.MIRROR_MODE)

    val command = task.commandLine(KeyRing(SystemUser(None)))

    command.quoted should be ("""rsync -e "ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no" -rpv --delete --delete-after /source foo.com:/dest""")
  }

  "S3Upload task" should "upload a single file to S3" in {

    val fileToUpload = new File("/foo/bar/the-jar.jar")


    val task = new S3Upload(UnnamedStack, Stage("CODE"), "bucket", fileToUpload) with StubS3

    task.execute()
    val s3Client = task.s3client(fakeKeyRing)

    verify(s3Client).putObject(any(classOf[PutObjectRequest]))
    verifyNoMoreInteractions(s3Client)
  }

  it should "create an upload request with correct permissions" in {
    val baseDir = createTempDir()
    val artifact = new File(baseDir, "artifact")
    artifact.createNewFile()

    val task = new S3Upload(UnnamedStack, Stage("CODE"), "bucket", baseDir)

    task.requests should not be ('empty)
    for (request <- task.requests) {
      request.getBucketName should be ("bucket")
      request.getCannedAcl should be (CannedAccessControlList.PublicRead)
      request.getFile should be (artifact)
      request.getKey should be ("CODE/" + baseDir.getName + "/artifact")
    }

    val taskWithoutAcl = task.copy(publicReadAcl = false)

    taskWithoutAcl.requests should not be ('empty)
    for (request <- taskWithoutAcl.requests) {
      request.getCannedAcl should be (null)
    }
  }

  it should "correctly convert a file to a key" in {
    val baseDir = new File("/foo/bar/something").getParentFile
    val child = new File(baseDir, "the/file/name.txt")

    val task = new S3Upload(UnnamedStack, Stage("CODE"), "bucket", baseDir)

    task.toKey(child) should be ("CODE/bar/the/file/name.txt")
  }

  it should "correctly convert a file to a key with prefixStage=false" in {
    val baseDir = new File("/foo/bar/something").getParentFile
    val child = new File(baseDir, "the/file/name.txt")

    val task = new S3Upload(UnnamedStack, Stage("CODE"), "bucket", baseDir, prefixStage = false)

    task.toKey(child) should be ("bar/the/file/name.txt")
  }

  it should "correctly convert a file to a key with prefixPackage=false" in {
    val baseDir = new File("/foo/bar/something").getParentFile
    val child = new File(baseDir, "the/file/name.txt")

    val task = new S3Upload(UnnamedStack, Stage("CODE"), "bucket", baseDir, prefixPackage = false)

    task.toKey(child) should be ("CODE/the/file/name.txt")
  }

  it should "correctly convert a file to a key with prefixStage=false and prefixPackage=false" in {
    val baseDir = new File("/foo/bar/something").getParentFile
    val child = new File(baseDir, "the/file/name.txt")

    val task = new S3Upload(UnnamedStack, Stage("CODE"), "bucket", baseDir, prefixStage = false, prefixPackage = false)

    task.toKey(child) should be ("the/file/name.txt")
  }

  it should "correctly convert a file to a key with a stack" in {
    val baseDir = new File("/packages/bar/something").getParentFile
    val child = new File(baseDir, "the/file/name.txt")

    val task = new S3Upload(NamedStack("monkey"), Stage("CODE"), "bucket", baseDir)

    task.toKey(child) should be ("monkey/CODE/bar/the/file/name.txt")
  }

  it should "correctly convert a file to a key with a stack and prefixStack=false" in {
    val baseDir = new File("/packages/bar/something").getParentFile
    val child = new File(baseDir, "the/file/name.txt")

    val task = new S3Upload(NamedStack("monkey"), Stage("CODE"), "bucket", baseDir, prefixStack = false)

    task.toKey(child) should be ("CODE/bar/the/file/name.txt")
  }

  it should "correctly convert a file to a key with a stack and prefixStack=false and prefixStage=false and prefixPackage=false" in {
    val baseDir = new File("/packages/bar/something").getParentFile
    val child = new File(baseDir, "the/file/name.txt")

    val task = new S3Upload(NamedStack("monkey"), Stage("CODE"), "bucket", baseDir, prefixStack = false, prefixStage = false, prefixPackage = false)

    task.toKey(child) should be ("the/file/name.txt")
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

    val task = new S3Upload(UnnamedStack, Stage("CODE"), "bucket", baseDir) with StubS3 {
      override val bucket = "bucket"
    }

    task.execute()
    val s3Client = task.s3client(fakeKeyRing)

    verify(s3Client, times(3)).putObject(any(classOf[PutObjectRequest]))

    verifyNoMoreInteractions(s3Client)
  }

  it should "use different cache control" in {
    val tempDir = createTempDir()
    val baseDir = new File(tempDir, "package")
    baseDir.mkdir()

    val fileOne = new File(baseDir, "one.txt")
    fileOne.createNewFile()
    val fileTwo = new File(baseDir, "two.txt")
    fileTwo.createNewFile()
    val subDir = new File(baseDir, "sub")
    subDir.mkdir()
    val fileThree = new File(subDir, "three.txt")
    fileThree.createNewFile()

    val cacheControlPatterns = List(PatternValue("^package/sub/", "public; max-age=3600"), PatternValue(".*", "no-cache"))
    val task = new S3Upload(UnnamedStack, Stage("CODE"), "bucket", baseDir, cacheControlPatterns) with StubS3 {
      override val bucket = "bucket"
    }

    task.requests.find(_.getFile == fileOne).get.getMetadata.getCacheControl should be("no-cache")
    task.requests.find(_.getFile == fileTwo).get.getMetadata.getCacheControl should be("no-cache")
    task.requests.find(_.getFile == fileThree).get.getMetadata.getCacheControl should be("public; max-age=3600")
  }

  "CleanupOldDeploy task" should "keep all deploys by default" in {
    val host = Host("some-host") as ("some-user")

    val task = new CleanupOldDeploys(host)
    val command = task.commandLine

    command.quoted should be ("")
  }

  it should "try to delete the last n deploys" in {
    val host = Host("some-host") as ("some-user")

    val task = new CleanupOldDeploys(host, 4)
    val command = task.commandLine

    command.quoted should be ("""ls -tr --ignore=logs | head -n -8 | xargs rm -rf""")
  }

  private def createTempDir(): File = {
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

  val parameters = DeployParameters(Deployer("tester"), Build("Project","1"), Stage("CODE"), RecipeName("baseRecipe.name"))
}


class TestServer(port:Int = 9997) {

  def withResponse(response: String) {
    val server = new ServerSocket(port)
    val socket = server.accept()
    val osw = new OutputStreamWriter(socket.getOutputStream)
    osw.write("%s\r\n\r\n" format (response))
    osw.flush()
    socket.close()
    server.close()
  }

}