package magenta
package tasks

import java.io.{File, OutputStreamWriter}
import java.net.ServerSocket
import java.util.UUID

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{CannedAccessControlList, PutObjectRequest}
import magenta.deployment_type.PatternValue
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}


class TasksTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())

  "S3Upload" should "upload a single file to S3" in {
    val fileToUpload = new File("/foo/bar/the-jar.jar")
    val task = new S3Upload("bucket", Seq((fileToUpload -> "keyPrefix/the-jar.jar"))) with StubS3

    val requests = task.requests
    requests.size should be (1)
    val request = requests.head
    request.getBucketName should be ("bucket")
    request.getFile should be(fileToUpload)
    request.getKey should be (s"keyPrefix/the-jar.jar")

    task.execute(reporter)
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
    task.execute(reporter)
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
    task.execute(reporter)
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