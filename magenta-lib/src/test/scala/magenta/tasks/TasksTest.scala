package magenta
package tasks

import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.util.UUID

import magenta.artifact.{S3Path, S3Object => MagentaS3Object}
import magenta.deployment_type.param_reads.PatternValue
import magenta.input.All
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model._


class TasksTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  val resources = mock[DeploymentResources].copy(reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters()))


  "PutRec" should "create an upload request with correct permissions" in {
    val putRec = PutReq(
      source = MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.jar", 31),
      target = S3Path("artifact-bucket", "foo/bar/the-jar.jar"),
      cacheControl = None, contentType = None,
      publicReadAcl = false
    )

    val awsRequest = putRec.toAwsRequest

    awsRequest.bucket should be ("artifact-bucket")
    awsRequest.acl should be (null)
    awsRequest.key should be ("foo/bar/the-jar.jar")
    Option(awsRequest.cacheControl) should be (None)
    awsRequest.contentType should be ("application/java-archive")

    val reqWithoutAcl = putRec.copy(publicReadAcl = true)

    val awsRequestWithoutAcl = reqWithoutAcl.toAwsRequest

    awsRequestWithoutAcl.acl should be (ObjectCannedACL.PUBLIC_READ)
  }

  it should "create an upload request with cache control and content type" in {
    val putRec = PutReq(
      MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.jar", 31),
      S3Path("artifact-bucket", "foo/bar/the-jar.jar"),
      Some("no-cache"), Some("application/json"),
      publicReadAcl = false
    )

    val awsRequest = putRec.toAwsRequest

    awsRequest.bucket should be ("artifact-bucket")
    awsRequest.acl should be (null)
    awsRequest.key should be ("foo/bar/the-jar.jar")
    Option(awsRequest.cacheControl) should be (Some("no-cache"))
    Option(awsRequest.contentType) should be (Some("application/json"))
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
      S3Path("artifact-bucket", "foo/bar/the-jar.abc"),
      Some("no-cache"), None,
      publicReadAcl = false
    )
    val putRecThree = PutReq(
      MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.js", 31),
      S3Path("artifact-bucket", "foo/bar/the-jar.js"),
      Some("no-cache"), None,
      publicReadAcl = false
    )

    val awsRequestOne = putRecOne.toAwsRequest
    val awsRequestTwo = putRecTwo.toAwsRequest
    val awsRequestThree = putRecThree.toAwsRequest

    awsRequestOne.contentType should be ("text/css")
    awsRequestTwo.contentType should be ("application/octet-stream")
    awsRequestThree.contentType should be ("application/javascript")
  }

  "S3Upload" should "upload a single file to S3" in {
    val resources = mock[DeploymentResources]
    val s3Client = mock[S3Client]

    val sourceBucket = "artifact-bucket"
    val targetBucket = "destination-bucket"
    val sourceKey = "foo/bar/the-jar.jar"
    val targetKey = "keyPrefix/the-jar.jar"

    val objectResult = mockListObjectsResponse(List(MagentaS3Object(sourceBucket, sourceKey, 31)))
    when(resources.artifactClient.listObjectsV2(any[ListObjectsV2Request])).thenReturn(objectResult)

    when(resources.artifactClient.getObjectAsBytes(GetObjectRequest.builder()
      .bucket(sourceBucket)
      .key(sourceKey)
        .build())
    ).thenReturn(mockGetObjectAsBytesResponse())

    val putObjectResult = PutObjectResponse.builder().sseCustomerKeyMD5("testMd5Sum").build()
    when(s3Client.putObject(any[PutObjectRequest], any[RequestBody])).thenReturn(putObjectResult)

    val fileToUpload = new S3Path(sourceBucket, sourceKey)
    val task = S3Upload(Region("eu-west-1"), targetBucket, Seq(fileToUpload -> targetKey), mock[DeploymentResources])(fakeKeyRing, clientFactory(s3Client))
    val mappings = task.objectMappings
    mappings.size should be (1)
    val (source, target) = mappings.head
    source.bucket should be (sourceBucket)
    source.key should be (sourceKey)
    source.size should be (31)

    target.bucket should be (targetBucket)
    target.key should be (targetKey)

    task.execute(resources)

    val request: ArgumentCaptor[PutObjectRequest] = ArgumentCaptor.forClass(classOf[PutObjectRequest])
    verify(s3Client).putObject(request.capture(), any[RequestBody])
    verifyNoMoreInteractions(s3Client)
    request.getValue.key should be ("keyPrefix/the-jar.jar")
    request.getValue.bucket should be ("destination-bucket")
  }

  it should "upload a directory to S3" in {
    val artifactClient = mock[S3Client]
    val s3Client = mock[S3Client]

    val fileOne = MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31)
    val fileTwo = MagentaS3Object("artifact-bucket", "test/123/package/two.txt", 31)
    val fileThree = MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)

    val objectResult = mockListObjectsResponse(List(fileOne, fileTwo, fileThree))
    when(artifactClient.listObjectsV2(any[ListObjectsV2Request])).thenReturn(objectResult)

    when(artifactClient.getObjectAsBytes(any[GetObjectRequest])).thenReturn(mockGetObjectAsBytesResponse())

    val putObjectResult = PutObjectResponse.builder().sseCustomerKeyMD5("testMd5Sum").build()
    when(s3Client.putObject(any[PutObjectRequest], any[RequestBody])).thenReturn(putObjectResult)

    val packageRoot = new S3Path("artifact-bucket", "test/123/package/")

    val task = new S3Upload(Region("eu-west-1"), "bucket", Seq(packageRoot -> "myStack/CODE/myApp"), mock[DeploymentResources])(fakeKeyRing, clientFactory(s3Client))

    task.execute(resources)

    val files = task.objectMappings
    files.size should be (3)
    files should contain ((fileOne, S3Path("bucket", "myStack/CODE/myApp/one.txt")))
    files should contain ((fileTwo, S3Path("bucket", "myStack/CODE/myApp/two.txt")))
    files should contain ((fileThree, S3Path("bucket", "myStack/CODE/myApp/sub/three.txt")))

    verify(s3Client, times(3)).putObject(any[PutObjectRequest], any[RequestBody])

    verifyNoMoreInteractions(s3Client)
  }

  it should "upload a directory to S3 with no prefix" in {
    val artifactClient = mock[S3Client]
    val s3Client = mock[S3Client]

    val fileOne = MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31)
    val fileTwo = MagentaS3Object("artifact-bucket", "test/123/package/two.txt", 31)
    val fileThree = MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)

    val objectResult = mockListObjectsResponse(List(fileOne, fileTwo, fileThree))
    when(artifactClient.listObjectsV2(any[ListObjectsV2Request])).thenReturn(objectResult)

    when(artifactClient.getObjectAsBytes(any[GetObjectRequest])).thenReturn(mockGetObjectAsBytesResponse())

    val putObjectResult = PutObjectResponse.builder().sseCustomerKeyMD5("testMd5Sum").build()
    when(s3Client.putObject(any[PutObjectRequest], any[RequestBody])).thenReturn(putObjectResult)

    val packageRoot = new S3Path("artifact-bucket", "test/123/package/")

     val task = new S3Upload(Region("eu-west-1"), "bucket", Seq(packageRoot -> ""), resources)(fakeKeyRing,  clientFactory(s3Client))
    task.execute(resources)

    val files = task.objectMappings
    files.size should be (3)
    // these should have no initial '/' in the target key
    files should contain ((fileOne, S3Path("bucket", "one.txt")))
    files should contain ((fileTwo, S3Path("bucket", "two.txt")))
    files should contain ((fileThree, S3Path("bucket", "sub/three.txt")))

    verify(s3Client, times(3)).putObject(any[PutObjectRequest], any[RequestBody])

    verifyNoMoreInteractions(s3Client)
  }

  it should "use different cache control" in {
    val artifactClient = mock[S3Client]
    val fileOne = MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31)
    val fileTwo = MagentaS3Object("artifact-bucket", "test/123/package/two.txt", 31)
    val fileThree = MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)
    val objectResult = mockListObjectsResponse(List(fileOne, fileTwo, fileThree))
    when(artifactClient.listObjectsV2(any[ListObjectsV2Request])).thenReturn(objectResult)

    val patternValues = List(PatternValue("^keyPrefix/sub/", "public; max-age=3600"), PatternValue(".*", "no-cache"))
    val packageRoot = new S3Path("artifact-bucket", "test/123/package/")
    val task = new S3Upload(Region("eu-west-1"), "bucket", Seq(packageRoot -> "keyPrefix"), mock[DeploymentResources], cacheControlPatterns = patternValues)(fakeKeyRing)

    task.requests.find(_.source == fileOne).get.cacheControl should be(Some("no-cache"))
    task.requests.find(_.source == fileTwo).get.cacheControl should be(Some("no-cache"))
    task.requests.find(_.source == fileThree).get.cacheControl should be(Some("public; max-age=3600"))
  }

  it should "use overridden mime type" in {
    val artifactClient = mock[S3Client]
    val fileOne = MagentaS3Object("artifact-bucket", "test/123/package/one.test.txt", 31)
    val fileTwo = MagentaS3Object("artifact-bucket", "test/123/package/two.test.xpi", 31)
    val objectResult = mockListObjectsResponse(List(fileOne, fileTwo))
    when(artifactClient.listObjectsV2(any[ListObjectsV2Request])).thenReturn(objectResult)

    val mimeTypes = Map("xpi" -> "application/x-xpinstall")
    val packageRoot = new S3Path("artifact-bucket", "test/123/package/")
    val task = new S3Upload(Region("eu-west-1"), "bucket", Seq(packageRoot -> ""), mock[DeploymentResources], extensionToMimeType = mimeTypes)(fakeKeyRing)

    task.requests.find(_.source == fileOne).get.contentType should be(None)
    task.requests.find(_.source == fileTwo).get.contentType should be(Some("application/x-xpinstall"))
  }

  def mockListObjectsResponse(objs: List[MagentaS3Object]): ListObjectsV2Response = {
    val s3Objects = objs.map { obj =>
      S3Object.builder().key(obj.key).size(obj.size).build()
    }

    ListObjectsV2Response.builder().contents(s3Objects:_*).build()
  }

  def mockGetObjectAsBytesResponse(): ResponseBytes[GetObjectResponse] = {
    val stream = "Some content for this S3Object.".getBytes("UTF-8")
    ResponseBytes.fromByteArray(
      GetObjectResponse.builder().contentLength(31L).build(),
      stream)
  }

  def clientFactory(client: S3Client): (KeyRing, Region, ClientOverrideConfiguration, DeploymentResources) => (S3Client => Unit) => Unit = { (_, _, _, _) => block => block(client) }

  val parameters = DeployParameters(Deployer("tester"), Build("Project","1"), Stage("CODE"), All)
}


class TestServer(port:Int = 9997) {

  def withResponse(response: String) {
    val server = new ServerSocket(port)
    val socket = server.accept()
    val osw = new OutputStreamWriter(socket.getOutputStream)
    osw.write("%s\r\n\r\n" format response)
    osw.flush()
    socket.close()
    server.close()
  }

}