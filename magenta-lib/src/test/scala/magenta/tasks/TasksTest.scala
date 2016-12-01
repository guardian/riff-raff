package magenta
package tasks

import java.io.{ByteArrayInputStream, OutputStreamWriter}
import java.net.ServerSocket
import java.util
import java.util.UUID

import com.amazonaws.ClientConfiguration
import com.amazonaws.services.s3.model._
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import magenta.artifact.{S3Path, S3Object => MagentaS3Object}
import magenta.deployment_type.param_reads.PatternValue
import org.mockito.ArgumentCaptor
import org.mockito.Matchers.any
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import magenta.Region

import scala.collection.JavaConverters._


class TasksTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())

  "PutRec" should "create an upload request with correct permissions" in {
    val putRec = PutReq(
      MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.jar", 31),
      S3Path("artifact-bucket", "foo/bar/the-jar.jar"),
      None, None,
      publicReadAcl = false
    )

    val stream = new ByteArrayInputStream("bob".getBytes("UTF-8"))
    val awsRequest = putRec.toAwsRequest(stream)

    awsRequest.getBucketName should be ("artifact-bucket")
    awsRequest.getCannedAcl should be (null)
    awsRequest.getKey should be ("foo/bar/the-jar.jar")
    awsRequest.getInputStream should be (stream)
    Option(awsRequest.getMetadata.getCacheControl) should be (None)
    awsRequest.getMetadata.getContentType should be ("application/octet-stream")

    val reqWithoutAcl = putRec.copy(publicReadAcl = true)

    val awsRequestWithoutAcl = reqWithoutAcl.toAwsRequest(stream)

    awsRequestWithoutAcl.getCannedAcl should be (CannedAccessControlList.PublicRead)
  }

  it should "create an upload request with cache control and content type" in {
    val putRec = PutReq(
      MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.jar", 31),
      S3Path("artifact-bucket", "foo/bar/the-jar.jar"),
      Some("no-cache"), Some("application/json"),
      publicReadAcl = false
    )

    val stream = new ByteArrayInputStream("bob".getBytes("UTF-8"))
    val awsRequest = putRec.toAwsRequest(stream)

    awsRequest.getBucketName should be ("artifact-bucket")
    awsRequest.getCannedAcl should be (null)
    awsRequest.getKey should be ("foo/bar/the-jar.jar")
    awsRequest.getInputStream should be (stream)
    Option(awsRequest.getMetadata.getCacheControl) should be (Some("no-cache"))
    Option(awsRequest.getMetadata.getContentType) should be (Some("application/json"))
  }

  it should "use a default mime type from S3" in {
    val putRecOne = PutReq(
      MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.css", 31),
      S3Path("artifact-bucket", "foo/bar/the-jar.css"),
      Some("no-cache"), None,
      publicReadAcl = false
    )
    val putRecTwo = PutReq(
      MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.xpi", 31),
      S3Path("artifact-bucket", "foo/bar/the-jar.xpi"),
      Some("no-cache"), None,
      publicReadAcl = false
    )
    val putRecThree = PutReq(
      MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.js", 31),
      S3Path("artifact-bucket", "foo/bar/the-jar.js"),
      Some("no-cache"), None,
      publicReadAcl = false
    )

    val stream = new ByteArrayInputStream("bob".getBytes("UTF-8"))
    val awsRequestOne = putRecOne.toAwsRequest(stream)
    val awsRequestTwo = putRecTwo.toAwsRequest(stream)
    val awsRequestThree = putRecThree.toAwsRequest(stream)

    awsRequestOne.getMetadata.getContentType should be ("text/css")
    awsRequestTwo.getMetadata.getContentType should be ("application/octet-stream")
    awsRequestThree.getMetadata.getContentType should be ("application/x-javascript")
  }

  "S3Upload" should "upload a single file to S3" in {
    val artifactClient = mock[AmazonS3Client]
    val objectResult = mockListObjects(List(MagentaS3Object("artifact-bucket", "foo/bar/the-jar.jar", 31)))
    when(artifactClient.listObjectsV2(any[ListObjectsV2Request])).thenReturn(objectResult)
    when(artifactClient.getObject("artifact-bucket", "foo/bar/the-jar.jar")).thenReturn(mockObject("Some content for this S3 object"))

    val fileToUpload = new S3Path("artifact-bucket", "foo/bar/the-jar.jar")
    val s3Client = mock[AmazonS3Client]
    val putObjectResult = {
      val por = new PutObjectResult
      por.setContentMd5("testMd5Sum")
      val metadata = new ObjectMetadata()
      metadata.setContentLength(300)
      por.setMetadata(metadata)
      por
    }
    when(s3Client.putObject(any[PutObjectRequest])).thenReturn(putObjectResult)
    val task = new S3Upload(Region("eu-west-1"), "destination-bucket", Seq(fileToUpload -> "keyPrefix/the-jar.jar"))(fakeKeyRing, artifactClient, clientFactory(s3Client))

    val mappings = task.objectMappings
    mappings.size should be (1)
    val (source, target) = mappings.head
    source.bucket should be ("artifact-bucket")
    source.key should be ("foo/bar/the-jar.jar")
    source.size should be (31)

    target.bucket should be ("destination-bucket")
    target.key should be ("keyPrefix/the-jar.jar")

    task.execute(reporter)

    val request = ArgumentCaptor.forClass(classOf[PutObjectRequest])
    verify(s3Client).putObject(request.capture())
    verifyNoMoreInteractions(s3Client)
    request.getValue.getKey should be ("keyPrefix/the-jar.jar")
    request.getValue.getBucketName should be ("destination-bucket")
  }

  it should "upload a directory to S3" in {
    val artifactClient = mock[AmazonS3Client]
    val fileOne = MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31)
    val fileTwo = MagentaS3Object("artifact-bucket", "test/123/package/two.txt", 31)
    val fileThree = MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)
    val objectResult = mockListObjects(List(fileOne, fileTwo, fileThree))
    when(artifactClient.listObjectsV2(any[ListObjectsV2Request])).thenReturn(objectResult)
    when(artifactClient.getObject(any[String], any[String])).thenReturn(mockObject("Some content for this S3 object"))

    val packageRoot = new S3Path("artifact-bucket", "test/123/package/")
    val s3Client = mock[AmazonS3Client]
    val putObjectResult = {
      val por = new PutObjectResult
      por.setContentMd5("testMd5Sum")
      val metadata = new ObjectMetadata()
      metadata.setContentLength(300)
      por.setMetadata(metadata)
      por
    }
    when(s3Client.putObject(any[PutObjectRequest])).thenReturn(putObjectResult)
    val task = new S3Upload(Region("eu-west-1"), "bucket", Seq(packageRoot -> "myStack/CODE/myApp"))(fakeKeyRing, artifactClient, clientFactory(s3Client))
    task.execute(reporter)

    val files = task.objectMappings
    files.size should be (3)
    files should contain ((fileOne, S3Path("bucket", "myStack/CODE/myApp/one.txt")))
    files should contain ((fileTwo, S3Path("bucket", "myStack/CODE/myApp/two.txt")))
    files should contain ((fileThree, S3Path("bucket", "myStack/CODE/myApp/sub/three.txt")))

    verify(s3Client, times(3)).putObject(any(classOf[PutObjectRequest]))

    verifyNoMoreInteractions(s3Client)
  }

  it should "upload a directory to S3 with no prefix" in {

    val artifactClient = mock[AmazonS3Client]
    val fileOne = MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31)
    val fileTwo = MagentaS3Object("artifact-bucket", "test/123/package/two.txt", 31)
    val fileThree = MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)
    val objectResult = mockListObjects(List(fileOne, fileTwo, fileThree))
    when(artifactClient.listObjectsV2(any[ListObjectsV2Request])).thenReturn(objectResult)
    when(artifactClient.getObject(any[String], any[String])).thenReturn(mockObject("Some content for this S3 object"))

    val packageRoot = new S3Path("artifact-bucket", "test/123/package/")
    val s3Client = mock[AmazonS3Client]
    val putObjectResult = {
      val por = new PutObjectResult
      por.setContentMd5("testMd5Sum")
      val metadata = new ObjectMetadata()
      metadata.setContentLength(300)
      por.setMetadata(metadata)
      por
    }
    when(s3Client.putObject(any[PutObjectRequest])).thenReturn(putObjectResult)
    val task = new S3Upload(Region("eu-west-1"), "bucket", Seq(packageRoot -> ""))(fakeKeyRing, artifactClient, clientFactory(s3Client))
    task.execute(reporter)

    val files = task.objectMappings
    files.size should be (3)
    // these should have no initial '/' in the target key
    files should contain ((fileOne, S3Path("bucket", "one.txt")))
    files should contain ((fileTwo, S3Path("bucket", "two.txt")))
    files should contain ((fileThree, S3Path("bucket", "sub/three.txt")))

    verify(s3Client, times(3)).putObject(any(classOf[PutObjectRequest]))

    verifyNoMoreInteractions(s3Client)
  }

  it should "use different cache control" in {
    val artifactClient = mock[AmazonS3Client]
    val fileOne = MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31)
    val fileTwo = MagentaS3Object("artifact-bucket", "test/123/package/two.txt", 31)
    val fileThree = MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)
    val objectResult = mockListObjects(List(fileOne, fileTwo, fileThree))
    when(artifactClient.listObjectsV2(any[ListObjectsV2Request])).thenReturn(objectResult)

    val patternValues = List(PatternValue("^keyPrefix/sub/", "public; max-age=3600"), PatternValue(".*", "no-cache"))
    val packageRoot = new S3Path("artifact-bucket", "test/123/package/")
    val task = new S3Upload(Region("eu-west-1"), "bucket", Seq(packageRoot -> "keyPrefix"), cacheControlPatterns = patternValues)(fakeKeyRing, artifactClient)

    task.requests.find(_.source == fileOne).get.cacheControl should be(Some("no-cache"))
    task.requests.find(_.source == fileTwo).get.cacheControl should be(Some("no-cache"))
    task.requests.find(_.source == fileThree).get.cacheControl should be(Some("public; max-age=3600"))
  }

  it should "use overridden mime type" in {
    val artifactClient = mock[AmazonS3Client]
    val fileOne = MagentaS3Object("artifact-bucket", "test/123/package/one.test.txt", 31)
    val fileTwo = MagentaS3Object("artifact-bucket", "test/123/package/two.test.xpi", 31)
    val objectResult = mockListObjects(List(fileOne, fileTwo))
    when(artifactClient.listObjectsV2(any[ListObjectsV2Request])).thenReturn(objectResult)

    val mimeTypes = Map("xpi" -> "application/x-xpinstall")
    val packageRoot = new S3Path("artifact-bucket", "test/123/package/")
    val task = new S3Upload(Region("eu-west-1"), "bucket", Seq(packageRoot -> ""), extensionToMimeType = mimeTypes)(fakeKeyRing, artifactClient)

    task.requests.find(_.source == fileOne).get.contentType should be(None)
    task.requests.find(_.source == fileTwo).get.contentType should be(Some("application/x-xpinstall"))
  }

  def mockListObjects(objs: List[MagentaS3Object]) = {
    val summaries = objs.map { obj =>
      val summary = new S3ObjectSummary()
      summary.setBucketName(obj.bucket)
      summary.setKey(obj.key)
      summary.setSize(obj.size)
      summary
    }
    new ListObjectsV2Result() {
      override def getObjectSummaries: util.List[S3ObjectSummary] = {
        summaries.asJava
      }
    }
  }

  def mockObject(content: String): S3Object = {
    val mockedObject = new S3Object()
    mockedObject.setObjectContent(new ByteArrayInputStream(content.getBytes("UTF-8")))
    mockedObject
  }
  
  def clientFactory(client: AmazonS3): (KeyRing, Region, ClientConfiguration) => AmazonS3 = { (_, _, _) => client }

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