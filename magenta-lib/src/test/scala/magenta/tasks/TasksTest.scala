package magenta
package tasks

import org.scalatest.{Matchers, FlatSpec}
import java.net.ServerSocket
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


class TasksTest extends FlatSpec with Matchers with MockitoSugar {
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

    val task = Service(host, "app")

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

    val task = Service(host, "myapp")

    task.commandLine should be (CommandLine(List("sudo", "/sbin/service", "myapp", "restart")))
  }

  "waitForPort task" should "fail after timeout" in {
    val task = WaitForPort(Host("localhost"), 9998, 200)
    a [FailException] should be thrownBy {
      task.execute()
    }
  }

  it should "connect to open port" in {
    val task = WaitForPort(Host("localhost"), 9998, 200)
    Future {
      val server = new ServerSocket(9998)
      server.accept().close()
      server.close()
    }
    DeployLogger.deployContext(UUID.randomUUID(), parameters) { task.execute() }
  }

  it should "connect to an open port after a short time" in {
    val task = WaitForPort(Host("localhost"), 9997, 1000)
    Future {
      Thread.sleep(600)
      val server = new ServerSocket(9997)
      server.accept().close()
      server.close()
    }
    task.execute()
  }


  "check_url task" should "fail after timeout" in {
    val task = CheckUrls(Host("localhost"), 9997,List("/"), 200, 5)
    a [FailException] should be thrownBy {
      task.execute()
    }
  }

  it should "get a 200 OK" in {
    val task = CheckUrls(Host("localhost"), 9997, List("/"), 200, 5)
    Future {
      new TestServer().withResponse("HTTP/1.0 200 OK")
    }
    task.execute()

  }

  it should "fail on a 404 NOT FOUND" in {
    val task = CheckUrls(Host("localhost"), 9997, List("/"), 200, 5)
    Future {
      new TestServer().withResponse("HTTP/1.0 404 NOT FOUND")
    }
    a [FailException] should be thrownBy {
      task.execute()
    }
  }

  it should "fail on a 500 ERROR" in {
    val task = CheckUrls(Host("localhost"), 9997, List("/"), 200, 5)
    Future {
      new TestServer().withResponse("HTTP/1.0 500 ERROR")
    }
    a [FailException] should be thrownBy {
      task.execute()
    }
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

  "S3Upload" should "upload a single file to S3" in {
    val fileToUpload = new File("/foo/bar/the-jar.jar")
    val task = new S3Upload("bucket", Seq((fileToUpload -> "keyPrefix/the-jar.jar"))) with StubS3

    val requests = task.requests
    requests.size should be (1)
    val request = requests.head
    request.getBucketName should be ("bucket")
    request.getFile should be(fileToUpload)
    request.getKey should be (s"keyPrefix/the-jar.jar")

    task.execute()
    val s3Client = task.s3client(fakeKeyRing)

    verify(s3Client).putObject(request)
    verifyNoMoreInteractions(s3Client)
  }

  it should "create an upload request with correct permissions" in {
    val baseDir = createTempDir()
    val artifact = new File(baseDir, "artifact")
    artifact.createNewFile()

    val task = new S3Upload("bucket", Seq((baseDir -> "bucket")))

    task.requests should not be ('empty)
    for (request <- task.requests) {
      request.getBucketName should be ("bucket")
      request.getCannedAcl should be (null)
      request.getFile should be (artifact)
      request.getKey should be ("bucket/artifact")
    }

    val taskWithoutAcl = task.copy(publicReadAcl = true)

    taskWithoutAcl.requests should not be ('empty)
    for (request <- taskWithoutAcl.requests) {
      request.getCannedAcl should be (CannedAccessControlList.PublicRead)
    }
  }

  it should "upload a directory to S3" in {

    val baseDir = createTempDir()

    val fileOne = new File(baseDir, "one.txt")
    fileOne.createNewFile()
    val fileTwo = new File(baseDir, "two.txt")
    fileTwo.createNewFile()
    val subDir = new File(baseDir, "sub")
    subDir.mkdir()
    val fileThree = new File(subDir, "three.txt")
    fileThree.createNewFile()

    val task = new S3Upload("bucket", Seq((baseDir -> "myStack/CODE/myApp"))) with StubS3
    task.execute()
    val s3Client = task.s3client(fakeKeyRing)

    val files = task.flattenedFiles
    files.size should be (3)
    files should contain ((fileOne,"myStack/CODE/myApp/one.txt"))
    files should contain ((fileTwo,"myStack/CODE/myApp/two.txt"))
    files should contain ((fileThree,"myStack/CODE/myApp/sub/three.txt"))

    verify(s3Client, times(3)).putObject(any(classOf[PutObjectRequest]))

    verifyNoMoreInteractions(s3Client)
  }

  it should "upload a directory to S3 with no prefix" in {

    val baseDir = createTempDir()

    val fileOne = new File(baseDir, "one.txt")
    fileOne.createNewFile()
    val fileTwo = new File(baseDir, "two.txt")
    fileTwo.createNewFile()
    val subDir = new File(baseDir, "sub")
    subDir.mkdir()
    val fileThree = new File(subDir, "three.txt")
    fileThree.createNewFile()

    val task = new S3Upload("bucket", Seq(baseDir -> "")) with StubS3
    task.execute()
    val s3Client = task.s3client(fakeKeyRing)

    val files = task.flattenedFiles
    files.size should be (3)
    // these should have no initial '/' in the target key
    files should contain ((fileOne,"one.txt"))
    files should contain ((fileTwo,"two.txt"))
    files should contain ((fileThree,"sub/three.txt"))

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

    val patternValues = List(PatternValue("^keyPrefix/sub/", "public; max-age=3600"), PatternValue(".*", "no-cache"))
    val task = new S3Upload("bucket", Seq((baseDir -> "keyPrefix")), cacheControlPatterns = patternValues) with StubS3

    task.requests.find(_.getFile == fileOne).get.getMetadata.getCacheControl should be("no-cache")
    task.requests.find(_.getFile == fileTwo).get.getMetadata.getCacheControl should be("no-cache")
    task.requests.find(_.getFile == fileThree).get.getMetadata.getCacheControl should be("public; max-age=3600")
  }

  it should "use overridden mime type" in {
    val tempDir = createTempDir()
    val baseDir = new File(tempDir, "package")
    baseDir.mkdir()

    val fileOne = new File(baseDir, "one.test.txt")
    fileOne.createNewFile()
    val fileTwo = new File(baseDir, "two.test.xpi")
    fileTwo.createNewFile()

    val mimeTypes = Map("xpi" -> "application/x-xpinstall")
    val task = new S3Upload("bucket", Seq((baseDir -> "")), extensionToMimeType = mimeTypes) with StubS3

    Option(task.requests.find(_.getFile == fileOne).get.getMetadata.getContentType) should be(None)
    Option(task.requests.find(_.getFile == fileTwo).get.getMetadata.getContentType) should be(Some("application/x-xpinstall"))
  }

  "CleanupOldDeploy task" should "keep all deploys by default" in {
    val host = Host("some-host") as ("some-user")

    val task = new CleanupOldDeploys(host, 0, "/tmp/", "test")
    val command = task.tasks

    command should be (Seq.empty)
  }

  it should "try to delete the last n deploys compressed files" in {
    val host = Host("some-host") as ("some-user")

    val task = new deleteCompressedFiles(host, "/tmp/")

    val command = task.commandLine

    command.quoted should be ("""rm -rf /tmp/*.tar.bz2""")
  }

  it should "try to delete the last n deploys" in {
    val host = Host("some-host") as ("some-user")

    val task = new deleteOldDeploys(host, 4, "/django-apps/", "test")

    val command = task.commandLine

    command.quoted should be ("""ls -tdr /django-apps/test?* | head -n -4 | xargs -t -n1 rm -rf""")
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