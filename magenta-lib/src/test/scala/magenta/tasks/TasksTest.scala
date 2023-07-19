package magenta
package tasks

import java.io.{ByteArrayInputStream, OutputStreamWriter}
import java.net.ServerSocket
import java.util.UUID
import com.google.api.client.http.AbstractInputStreamContent
import com.google.api.services.storage.Storage
import com.google.api.services.storage.model.{Objects, StorageObject}
import magenta.Strategy.MostlyHarmless
import magenta.artifact.{S3Path, S3Object => MagentaS3Object}
import magenta.deployment_type.GcsTargetBucket
import magenta.deployment_type.param_reads.PatternValue
import magenta.input.All
import magenta.tasks.gcp.{GCSPath, GCSUpload}
import org.mockito.{ArgumentCaptor, ArgumentMatchers, MockitoSugar}
import org.mockito.ArgumentMatchers._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.sync.{RequestBody, ResponseTransformer}
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model._
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.utils.IoUtils
import scala.collection.JavaConverters._

import java.util.concurrent.Executors
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global

class TasksTest extends AnyFlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  val reporter =
    DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())

  "PutRec" should "create an upload request with correct permissions" in {
    val putRec = PutReq(
      source = MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.jar", 31),
      target = S3Path("artifact-bucket", "foo/bar/the-jar.jar"),
      cacheControl = None,
      surrogateControl = None,
      contentType = None,
      publicReadAcl = false,
      allowDeletionByLifecycleRule = false
    )

    val awsRequest = putRec.toAwsRequest

    awsRequest.bucket should be("artifact-bucket")
    awsRequest.acl should be(null)
    awsRequest.key should be("foo/bar/the-jar.jar")
    Option(awsRequest.cacheControl) should be(None)
    awsRequest.contentType should be("application/java-archive")

    val reqWithoutAcl = putRec.copy(publicReadAcl = true)

    val awsRequestWithoutAcl = reqWithoutAcl.toAwsRequest

    awsRequestWithoutAcl.acl should be(ObjectCannedACL.PUBLIC_READ)
  }

  it should "create an upload request with cache control and content type" in {
    val putRec = PutReq(
      MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.jar", 31),
      S3Path("artifact-bucket", "foo/bar/the-jar.jar"),
      Some("no-cache"),
      None,
      Some("application/json"),
      publicReadAcl = false,
      allowDeletionByLifecycleRule = false
    )

    val awsRequest = putRec.toAwsRequest

    awsRequest.bucket should be("artifact-bucket")
    awsRequest.acl should be(null)
    awsRequest.key should be("foo/bar/the-jar.jar")
    Option(awsRequest.cacheControl) should be(Some("no-cache"))
    Option(awsRequest.contentType) should be(Some("application/json"))
  }

  it should "use a default mime type from S3" in {
    val putRecOne = PutReq(
      MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.css", 31),
      S3Path("artifact-bucket", "foo/bar/the-jar.css"),
      Some("no-cache"),
      None,
      None,
      publicReadAcl = false,
      allowDeletionByLifecycleRule = false
    )
    val putRecTwo = PutReq(
      MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.xpi", 31),
      S3Path("artifact-bucket", "foo/bar/the-jar.abc"),
      Some("no-cache"),
      None,
      None,
      publicReadAcl = false,
      allowDeletionByLifecycleRule = false
    )
    val putRecThree = PutReq(
      MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.js", 31),
      S3Path("artifact-bucket", "foo/bar/the-jar.js"),
      Some("no-cache"),
      None,
      None,
      publicReadAcl = false,
      allowDeletionByLifecycleRule = false
    )

    val awsRequestOne = putRecOne.toAwsRequest
    val awsRequestTwo = putRecTwo.toAwsRequest
    val awsRequestThree = putRecThree.toAwsRequest

    awsRequestOne.contentType should be("text/css")
    awsRequestTwo.contentType should be("application/octet-stream")
    awsRequestThree.contentType should be("application/javascript")
  }

  it should "not add any S3 object tags by default" in {
    val putRec = PutReq(
      MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.jar", 31),
      S3Path("artifact-bucket", "foo/bar/the-jar.jar"),
      Some("no-cache"),
      None,
      Some("application/json"),
      publicReadAcl = false,
      allowDeletionByLifecycleRule = false
    )
    val awsRequest = putRec.toAwsRequest
    awsRequest.tagging should be(null)
  }

  it should "add the correct S3 object tags when building requests for Lambda artifacts" in {
    val putRec = PutReq(
      MagentaS3Object("artifact-bucket", "foo/bar/foo-bar.jar", 31),
      S3Path("artifact-bucket", "foo/bar/the-jar.jar"),
      Some("no-cache"),
      None,
      Some("application/json"),
      publicReadAcl = false,
      allowDeletionByLifecycleRule = true
    )
    val awsRequest = putRec.toAwsRequest
    awsRequest.tagging should be("allow-deletion-by-lifecycle-rule=true")
  }

  "S3Upload" should "upload a single file to S3" in {
    val artifactClient = mock[S3Client]
    val s3Client = mock[S3Client]

    val sourceBucket = "artifact-bucket"
    val targetBucket = "destination-bucket"
    val sourceKey = "foo/bar/the-jar.jar"
    val targetKey = "keyPrefix/the-jar.jar"

    val objectResult = mockListObjectsResponse(
      List(MagentaS3Object(sourceBucket, sourceKey, 31))
    )
    when(artifactClient.listObjectsV2(any[ListObjectsV2Request]))
      .thenReturn(objectResult)

    val getObjectRequest = GetObjectRequest
      .builder()
      .bucket(sourceBucket)
      .key(sourceKey)
      .build()
    when(
      artifactClient.getObject(
        ArgumentMatchers.eq(getObjectRequest),
        any[ResponseTransformer[GetObjectResponse, GetObjectResponse]]
      )
    ).thenAnswer((invocation: InvocationOnMock) => {
      val transformer = invocation
        .getArguments()(1)
        .asInstanceOf[ResponseTransformer[GetObjectResponse, GetObjectResponse]]
      val getObjectResponse = GetObjectResponse.builder.build
      val stream = mockAbortableInputStream
      transformer.transform(getObjectResponse, stream)
    })

    val putObjectData = mutable.Buffer[String]()

    when(s3Client.putObject(any[PutObjectRequest], any[RequestBody]))
      .thenAnswer((invocation: InvocationOnMock) => {
        val requestBody = invocation.getArguments()(1).asInstanceOf[RequestBody]
        val stream = requestBody.contentStreamProvider.newStream
        putObjectData.append(IoUtils.toUtf8String(stream))
        PutObjectResponse.builder.sseCustomerKeyMD5("testMd5Sum").build
      })

    val fileToUpload = new S3Path(sourceBucket, sourceKey)
    val task = S3Upload(
      Region("eu-west-1"),
      targetBucket,
      Seq(fileToUpload -> targetKey)
    )(fakeKeyRing, artifactClient, clientFactory(s3Client))
    val mappings = task.objectMappings
    mappings.size should be(1)
    val (source, target) = mappings.head
    source.bucket should be(sourceBucket)
    source.key should be(sourceKey)
    source.size should be(31)

    target.bucket should be(targetBucket)
    target.key should be(targetKey)

    val ioEc = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

    val resources =
      DeploymentResources(reporter, null, artifactClient, mock[StsClient], ioEc)

    task.execute(resources, stopFlag = false)

    val request: ArgumentCaptor[PutObjectRequest] =
      ArgumentCaptor.forClass(classOf[PutObjectRequest])
    verify(s3Client).putObject(request.capture(), any[RequestBody])
    verifyNoMoreInteractions(s3Client)
    request.getValue.key shouldBe "keyPrefix/the-jar.jar"
    request.getValue.bucket shouldBe "destination-bucket"
    putObjectData.length shouldBe 1
    putObjectData.head shouldBe "Some content for this S3Object."
  }

  it should "upload a directory to S3" in {
    val artifactClient = mock[S3Client]
    val s3Client = mock[S3Client]

    val fileOne =
      MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31)
    val fileTwo =
      MagentaS3Object("artifact-bucket", "test/123/package/two.txt", 31)
    val fileThree =
      MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)

    val objectResult =
      mockListObjectsResponse(List(fileOne, fileTwo, fileThree))
    when(artifactClient.listObjectsV2(any[ListObjectsV2Request]))
      .thenReturn(objectResult)

    when(
      artifactClient.getObject(
        any[GetObjectRequest],
        any[ResponseTransformer[GetObjectResponse, GetObjectResponse]]
      )
    )
      .thenReturn(GetObjectResponse.builder.build)

    val putObjectResult =
      PutObjectResponse.builder().sseCustomerKeyMD5("testMd5Sum").build()
    when(s3Client.putObject(any[PutObjectRequest], any[RequestBody]))
      .thenReturn(putObjectResult)

    val packageRoot = new S3Path("artifact-bucket", "test/123/package/")

    val task = new S3Upload(
      Region("eu-west-1"),
      "bucket",
      Seq(packageRoot -> "myStack/CODE/myApp")
    )(fakeKeyRing, artifactClient, clientFactory(s3Client))
    val resources = DeploymentResources(
      reporter,
      null,
      artifactClient,
      mock[StsClient],
      global
    )
    task.execute(resources, stopFlag = false)

    val files = task.objectMappings
    files.size should be(3)
    files should contain(
      (fileOne, S3Path("bucket", "myStack/CODE/myApp/one.txt"))
    )
    files should contain(
      (fileTwo, S3Path("bucket", "myStack/CODE/myApp/two.txt"))
    )
    files should contain(
      (fileThree, S3Path("bucket", "myStack/CODE/myApp/sub/three.txt"))
    )

    verify(s3Client, times(3))
      .putObject(any[PutObjectRequest], any[RequestBody])

    verifyNoMoreInteractions(s3Client)
  }

  it should "upload a directory to S3 with no prefix" in {
    val artifactClient = mock[S3Client]
    val s3Client = mock[S3Client]

    val fileOne =
      MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31)
    val fileTwo =
      MagentaS3Object("artifact-bucket", "test/123/package/two.txt", 31)
    val fileThree =
      MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)

    val objectResult =
      mockListObjectsResponse(List(fileOne, fileTwo, fileThree))
    when(artifactClient.listObjectsV2(any[ListObjectsV2Request]))
      .thenReturn(objectResult)

    when(
      artifactClient.getObject(
        any[GetObjectRequest],
        any[ResponseTransformer[GetObjectResponse, GetObjectResponse]]
      )
    )
      .thenReturn(GetObjectResponse.builder.build)

    val putObjectResult =
      PutObjectResponse.builder().sseCustomerKeyMD5("testMd5Sum").build()
    when(s3Client.putObject(any[PutObjectRequest], any[RequestBody]))
      .thenReturn(putObjectResult)

    val packageRoot = new S3Path("artifact-bucket", "test/123/package/")

    val task = new S3Upload(
      Region("eu-west-1"),
      "bucket",
      Seq(packageRoot -> "")
    )(fakeKeyRing, artifactClient, clientFactory(s3Client))
    val resources = DeploymentResources(
      reporter,
      null,
      artifactClient,
      mock[StsClient],
      global
    )
    task.execute(resources, stopFlag = false)

    val files = task.objectMappings
    files.size should be(3)
    // these should have no initial '/' in the target key
    files should contain((fileOne, S3Path("bucket", "one.txt")))
    files should contain((fileTwo, S3Path("bucket", "two.txt")))
    files should contain((fileThree, S3Path("bucket", "sub/three.txt")))

    verify(s3Client, times(3))
      .putObject(any[PutObjectRequest], any[RequestBody])

    verifyNoMoreInteractions(s3Client)
  }

  it should "use different cache control" in {
    val artifactClient = mock[S3Client]
    val fileOne =
      MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31)
    val fileTwo =
      MagentaS3Object("artifact-bucket", "test/123/package/two.txt", 31)
    val fileThree =
      MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)
    val objectResult =
      mockListObjectsResponse(List(fileOne, fileTwo, fileThree))
    when(artifactClient.listObjectsV2(any[ListObjectsV2Request]))
      .thenReturn(objectResult)

    val patternValues = List(
      PatternValue("^keyPrefix/sub/", "public; max-age=3600"),
      PatternValue(".*", "no-cache")
    )
    val packageRoot = new S3Path("artifact-bucket", "test/123/package/")
    val task = new S3Upload(
      Region("eu-west-1"),
      "bucket",
      Seq(packageRoot -> "keyPrefix"),
      cacheControlPatterns = patternValues
    )(fakeKeyRing, artifactClient)

    task.requests.find(_.source == fileOne).get.cacheControl should be(
      Some("no-cache")
    )
    task.requests.find(_.source == fileTwo).get.cacheControl should be(
      Some("no-cache")
    )
    task.requests.find(_.source == fileThree).get.cacheControl should be(
      Some("public; max-age=3600")
    )
  }

  it should "use overridden mime type" in {
    val artifactClient = mock[S3Client]
    val fileOne =
      MagentaS3Object("artifact-bucket", "test/123/package/one.test.txt", 31)
    val fileTwo =
      MagentaS3Object("artifact-bucket", "test/123/package/two.test.xpi", 31)
    val objectResult = mockListObjectsResponse(List(fileOne, fileTwo))
    when(artifactClient.listObjectsV2(any[ListObjectsV2Request]))
      .thenReturn(objectResult)

    val mimeTypes = Map("xpi" -> "application/x-xpinstall")
    val packageRoot = new S3Path("artifact-bucket", "test/123/package/")
    val task = new S3Upload(
      Region("eu-west-1"),
      "bucket",
      Seq(packageRoot -> ""),
      extensionToMimeType = mimeTypes
    )(fakeKeyRing, artifactClient)

    task.requests.find(_.source == fileOne).get.contentType should be(None)
    task.requests.find(_.source == fileTwo).get.contentType should be(
      Some("application/x-xpinstall")
    )
  }

  "GCSUpload" should "upload a single file to GCS" in new GcsFileUploadScope {

    override def magentaObjects: List[MagentaS3Object] =
      List(MagentaS3Object(sourceBucket, sourceKey, 31))

    val fileToUpload = new S3Path(sourceBucket, sourceKey)
    val task = GCSUpload(targetBucket, Seq(fileToUpload -> targetKey))(
      fakeKeyRing,
      artifactClient,
      storageClientFactory(storageClient)
    )
    val mappings = task.objectMappings
    mappings.size should be(1)
    val (source, target) = mappings.head
    source.bucket should be(sourceBucket)
    source.key should be(sourceKey)
    source.size should be(31)

    target.bucket should be(targetBucket.name)
    target.key should be(targetKey)

    val resources = DeploymentResources(
      reporter,
      null,
      artifactClient,
      mock[StsClient],
      global
    )

    task.execute(resources, stopFlag = false)

    val bucket: ArgumentCaptor[String] =
      ArgumentCaptor.forClass(classOf[String])
    val storageObject: ArgumentCaptor[StorageObject] =
      ArgumentCaptor.forClass(classOf[StorageObject])
    verify(storageObjects).insert(
      bucket.capture(),
      storageObject.capture(),
      any[AbstractInputStreamContent]
    )
    verifyNoMoreInteractions(storageObjects)
    bucket.getValue should be("destination-bucket")
    storageObject.getValue.getName should be("keyPrefix/the-jar.jar")
    storageObject.getValue.getBucket should be("destination-bucket")
  }

  it should "upload a directory to GCS" in new GcsDirUploadScope {

    val task = new GCSUpload(
      targetBucket,
      Seq(packageRoot -> "myStack/CODE/myApp")
    )(fakeKeyRing, artifactClient, storageClientFactory(storageClient))
    val resources = DeploymentResources(
      reporter,
      null,
      artifactClient,
      mock[StsClient],
      global
    )
    task.execute(resources, stopFlag = false)

    val files = task.objectMappings
    files.size should be(3)
    files should contain(
      (
        magentaObjects(0),
        GCSPath("destination-bucket", "myStack/CODE/myApp/one.txt")
      )
    )
    files should contain(
      (
        magentaObjects(1),
        GCSPath("destination-bucket", "myStack/CODE/myApp/two.txt")
      )
    )
    files should contain(
      (
        magentaObjects(2),
        GCSPath("destination-bucket", "myStack/CODE/myApp/sub/three.txt")
      )
    )

    verify(storageObjects, times(3)).insert(
      any[String],
      any[StorageObject],
      any[AbstractInputStreamContent]
    )

    verifyNoMoreInteractions(storageObjects)
  }

  it should "upload a directory to GCS with no prefix" in new GcsDirUploadScope {

    val task = new GCSUpload(targetBucket, Seq(packageRoot -> ""))(
      fakeKeyRing,
      artifactClient,
      storageClientFactory(storageClient)
    )
    val resources = DeploymentResources(
      reporter,
      null,
      artifactClient,
      mock[StsClient],
      global
    )
    task.execute(resources, stopFlag = false)

    val files = task.objectMappings
    files.size should be(3)
    // these should have no initial '/' in the target key
    files should contain(
      (magentaObjects(0), GCSPath("destination-bucket", "one.txt"))
    )
    files should contain(
      (magentaObjects(1), GCSPath("destination-bucket", "two.txt"))
    )
    files should contain(
      (magentaObjects(2), GCSPath("destination-bucket", "sub/three.txt"))
    )

    verify(storageObjects, times(3)).insert(
      any[String],
      any[StorageObject],
      any[AbstractInputStreamContent]
    )

    verifyNoMoreInteractions(storageObjects)
  }

  it should "delete any objects previously deployed not being re-deployed in a configured folder when their types are configured" in new GcsDeleteOnUploadScope {

    def dirName = "test/123"
    def dirsToPrune: List[String] = List(dirName)

    override def targetBucket: GcsTargetBucket =
      GcsTargetBucket("target-bucket", dirsToPrune, List("txt"))
    override def currentlyDeployedFileNames: List[(String, List[String])] =
      List((dirName, List("one.txt", "two.txt", "sub/three.txt")))
    override def magentaObjects: List[MagentaS3Object] =
      List(
        MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31),
        MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)
      )

    verify(storageObjects, times(1)).list(any[String])
    verify(storageObjects, times(2)).insert(
      any[String],
      any[StorageObject],
      any[AbstractInputStreamContent]
    )
    verify(storageObjects, times(1)).delete(any[String], any[String])
    verifyNoMoreInteractions(storageObjects)
  }

  it should "delete any objects previously deployed not being re-deployed in a configured folder when their types are configured with a prefix" in new GcsDeleteOnUploadScope {

    def dirName = "test/123"
    def dirsToPrune: List[String] = List(s"myStack/CODE/myApp/${dirName}")

    override def prefix: String = "myStack/CODE/myApp"
    override def targetBucket: GcsTargetBucket =
      GcsTargetBucket("target-bucket", dirsToPrune, List("txt"))
    override def currentlyDeployedFileNames: List[(String, List[String])] =
      List((dirName, List("one.txt", "two.txt", "sub/three.txt")))
    override def magentaObjects: List[MagentaS3Object] =
      List(
        MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31),
        MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)
      )

    verify(storageObjects, times(1)).list(any[String])
    verify(storageObjects, times(2)).insert(
      any[String],
      any[StorageObject],
      any[AbstractInputStreamContent]
    )
    verify(storageObjects, times(1)).delete(any[String], any[String])
    verifyNoMoreInteractions(storageObjects)
  }

  it should "delete objects previously redeployed in different configured directories" in new GcsDeleteOnUploadScope {
    def dirsToPrune: List[String] = List("test/123", "test/124")
    override def currentlyDeployedFileNames: List[(String, List[String])] =
      List(
        ("test/123", List("one.txt", "sub1/two.txt")),
        ("test/124", List("sub2/three.txt", "sub2/four.txt", "sub2/five.txt"))
      )
    override def targetBucket: GcsTargetBucket =
      GcsTargetBucket("target-bucket", dirsToPrune, List("txt"))
    override def magentaObjects: List[MagentaS3Object] =
      List(
        MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31),
        MagentaS3Object(
          "artifact-bucket",
          "test/123/package/sub2/three.txt",
          31
        )
      )

    verify(storageObjects, times(2)).list(any[String])
    verify(storageObjects, times(2)).insert(
      any[String],
      any[StorageObject],
      any[AbstractInputStreamContent]
    )
    // Delete everything in sub1 plus everything under test/124
    verify(storageObjects, times(4)).delete(any[String], any[String])
    verifyNoMoreInteractions(storageObjects)
  }

  it should "only delete files with a configured suffix" in new GcsDeleteOnUploadScope {
    def dirName = "test/123"
    def dirsToPrune: List[String] = List(dirName)

    override def targetBucket: GcsTargetBucket =
      GcsTargetBucket("target-bucket", dirsToPrune, List("txt"))
    override def currentlyDeployedFileNames: List[(String, List[String])] =
      List(
        (dirName, List("one.txt", "two.txt", "sub/three.txt", "sub/four.py"))
      )
    override def magentaObjects: List[MagentaS3Object] =
      List(
        MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31),
        MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)
      )

    verify(storageObjects, times(1)).list(any[String])
    verify(storageObjects, times(2)).insert(
      any[String],
      any[StorageObject],
      any[AbstractInputStreamContent]
    )
    verify(storageObjects, times(1)).delete(any[String], any[String])
    verifyNoMoreInteractions(storageObjects)
  }

  it should "only delete files with a different configured suffixes" in new GcsDeleteOnUploadScope {
    def dirName = "test/123"
    def dirsToPrune: List[String] = List(dirName)

    override def targetBucket: GcsTargetBucket =
      GcsTargetBucket("target-bucket", dirsToPrune, List("txt", "py"))
    override def currentlyDeployedFileNames: List[(String, List[String])] =
      List(
        (dirName, List("one.txt", "two.txt", "sub/three.txt", "sub/four.py"))
      )
    override def magentaObjects: List[MagentaS3Object] =
      List(
        MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31),
        MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)
      )

    verify(storageObjects, times(1)).list(any[String])
    verify(storageObjects, times(2)).insert(
      any[String],
      any[StorageObject],
      any[AbstractInputStreamContent]
    )
    verify(storageObjects, times(2)).delete(any[String], any[String])
    verifyNoMoreInteractions(storageObjects)
  }

  it should "handle configured suffixes which start with a dot" in new GcsDeleteOnUploadScope {
    def dirName = "test/123"
    def dirsToPrune: List[String] = List(dirName)

    override def targetBucket: GcsTargetBucket =
      GcsTargetBucket("target-bucket", dirsToPrune, List(".txt", ".py"))
    override def currentlyDeployedFileNames: List[(String, List[String])] =
      List(
        (dirName, List("one.txt", "two.txt", "sub/three.txt", "sub/four.py"))
      )
    override def magentaObjects: List[MagentaS3Object] =
      List(
        MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31),
        MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)
      )

    verify(storageObjects, times(1)).list(any[String])
    verify(storageObjects, times(2)).insert(
      any[String],
      any[StorageObject],
      any[AbstractInputStreamContent]
    )
    verify(storageObjects, times(2)).delete(any[String], any[String])
    verifyNoMoreInteractions(storageObjects)
  }

  it should "only delete files in a configured directory" in new GcsDeleteOnUploadScope {
    def dirName = "test/123"
    def dirsToPrune: List[String] = List(dirName)

    override def currentlyDeployedFileNames: List[(String, List[String])] =
      List(
        ("test/123", List("one.txt", "sub1/two.txt")),
        ("test/124", List("sub2/three.txt", "sub2/four.txt", "sub2/five.txt"))
      )
    override def targetBucket: GcsTargetBucket =
      GcsTargetBucket("target-bucket", dirsToPrune, List("txt"))
    override def magentaObjects: List[MagentaS3Object] =
      List(
        MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31),
        MagentaS3Object(
          "artifact-bucket",
          "test/123/package/sub2/three.txt",
          31
        )
      )

    verify(storageObjects, times(1)).list(any[String])
    verify(storageObjects, times(2)).insert(
      any[String],
      any[StorageObject],
      any[AbstractInputStreamContent]
    )
    verify(storageObjects, times(1)).delete(any[String], any[String])
    verifyNoMoreInteractions(storageObjects)
  }

  it should "use different cache control" in new GcsDirUploadScope {
    val patternValues = List(
      PatternValue("^keyPrefix/sub/", "public; max-age=3600"),
      PatternValue(".*", "no-cache")
    )
    val task = new GCSUpload(
      targetBucket,
      Seq(packageRoot -> "keyPrefix"),
      cacheControlPatterns = patternValues
    )(fakeKeyRing, artifactClient)

    task.transfers
      .find(_.source == magentaObjects(0))
      .get
      .target
      .getCacheControl should be("no-cache")
    task.transfers
      .find(_.source == magentaObjects(1))
      .get
      .target
      .getCacheControl should be("no-cache")
    task.transfers
      .find(_.source == magentaObjects(2))
      .get
      .target
      .getCacheControl should be("public; max-age=3600")
  }

  def mockListObjectsResponse(
      objs: List[MagentaS3Object]
  ): ListObjectsV2Response = {
    val s3Objects = objs.map { obj =>
      S3Object.builder().key(obj.key).size(obj.size).build()
    }

    ListObjectsV2Response.builder().contents(s3Objects: _*).build()
  }

  private val responseString = "Some content for this S3Object."
  private val responseData: Array[Byte] = responseString.getBytes("UTF-8")

  def mockGetObjectAsBytesResponse(): ResponseBytes[GetObjectResponse] = {
    ResponseBytes.fromByteArray(
      GetObjectResponse.builder().contentLength(31L).build(),
      responseData
    )
  }

  def mockAbortableInputStream: AbortableInputStream =
    AbortableInputStream.create(new ByteArrayInputStream(responseData))

  def clientFactory(
      client: S3Client
  ): (KeyRing, Region, ClientOverrideConfiguration, DeploymentResources) => (
      S3Client => Unit
  ) => Unit = { (_, _, _, _) => block => block(client) }
  def storageClientFactory(
      client: Storage
  ): (KeyRing, DeploymentResources) => (Storage => Unit) => Unit = {
    (_, _) => block => block(client)
  }

  val parameters = DeployParameters(
    Deployer("tester"),
    Build("Project", "1"),
    Stage("CODE"),
    All,
    updateStrategy = MostlyHarmless
  )

  trait GcsTestScope {
    val sourceKey = "foo/bar/the-jar.jar"
    val targetKey = "keyPrefix/the-jar.jar"

    val artifactClient = mock[S3Client]
    val storageClient = mock[Storage]
    val storageObjects = mock[storageClient.Objects]
    val storageObjectsInsert = mock[storageObjects.Insert]

    val sourceBucket = "artifact-bucket"
    val packageRoot = new S3Path("artifact-bucket", "test/123/package/")

    def targetBucket: GcsTargetBucket =
      GcsTargetBucket("destination-bucket", List.empty, List.empty)
    def magentaObjects: List[MagentaS3Object] =
      List(
        MagentaS3Object("artifact-bucket", "test/123/package/one.txt", 31),
        MagentaS3Object("artifact-bucket", "test/123/package/two.txt", 31),
        MagentaS3Object("artifact-bucket", "test/123/package/sub/three.txt", 31)
      )

    lazy val objectResult = mockListObjectsResponse(magentaObjects)

    when(artifactClient.listObjectsV2(any[ListObjectsV2Request]))
      .thenReturn(objectResult)

    val storageObjectResult = new StorageObject()
      .setBucket(targetBucket.name)
      .setName("keyPrefix/the-jar.jar")

    when(storageClient.objects()).thenReturn(storageObjects)
    when(
      storageObjects.insert(
        any[String],
        any[StorageObject],
        any[AbstractInputStreamContent]
      )
    ).thenReturn(storageObjectsInsert)
    when(storageObjectsInsert.execute()).thenReturn(storageObjectResult)
  }

  trait GcsFileUploadScope extends GcsTestScope {
    when(
      artifactClient.getObjectAsBytes(
        GetObjectRequest
          .builder()
          .bucket(sourceBucket)
          .key(sourceKey)
          .build()
      )
    ).thenReturn(mockGetObjectAsBytesResponse())
  }

  trait GcsDirUploadScope extends GcsTestScope {
    when(
      artifactClient.getObject(
        any[GetObjectRequest],
        any[ResponseTransformer[GetObjectResponse, GetObjectResponse]]
      )
    )
      .thenReturn(GetObjectResponse.builder.build)
    when(artifactClient.getObjectAsBytes(any[GetObjectRequest]))
      .thenReturn(mockGetObjectAsBytesResponse())
  }

  trait GcsDeleteOnUploadScope extends GcsDirUploadScope {

    def currentlyDeployedFileNames: List[(String, List[String])]
    def prefix = ""

    val storageObjectsList = mock[storageObjects.List]
    val storageObjectsDelete = mock[storageObjects.Delete]
    val objects = mock[Objects]

    val mockStorageObjectsbyDir = (for {
      (dirName, fileNames) <- currentlyDeployedFileNames
      mockObjects = fileNames.map { name =>
        val storageObjectName = if (prefix.isEmpty) name else s"$prefix/$name"
        val mockStorageObject = mock[StorageObject]
        when(mockStorageObject.getName).thenReturn(storageObjectName)
        mockStorageObject
      }
    } yield (dirName, mockObjects))

    mockStorageObjectsbyDir.map(_._1).foreach { dir =>
      val expectedDir = if (prefix.isEmpty) dir else s"$prefix/$dir"
      when(storageObjects.list(ArgumentMatchers.eq(targetBucket.name)))
        .thenReturn(storageObjectsList)
      when(storageObjectsList.setPrefix(ArgumentMatchers.eq(expectedDir)))
        .thenReturn(storageObjectsList)
      when(storageObjectsList.execute).thenReturn(objects)
      when(objects.getNextPageToken).thenReturn(null)
    }

    // Mock calls to different prefixes returning the expected list
    mockStorageObjectsbyDir.map(_._2.asJava) match {
      case Nil          =>
      case head :: Nil  => when(objects.getItems).thenReturn(head)
      case head :: tail => when(objects.getItems).thenReturn(head, tail: _*)
    }

    when(storageObjects.delete(any[String], any[String]))
      .thenReturn(storageObjectsDelete)
    val task = new GCSUpload(targetBucket, Seq(packageRoot -> prefix))(
      fakeKeyRing,
      artifactClient,
      storageClientFactory(storageClient)
    )
    val resources = DeploymentResources(
      reporter,
      null,
      artifactClient,
      mock[StsClient],
      global
    )
    task.execute(resources, stopFlag = false)
  }
}

class TestServer(port: Int = 9997) {

  def withResponse(response: String): Unit = {
    val server = new ServerSocket(port)
    val socket = server.accept()
    val osw = new OutputStreamWriter(socket.getOutputStream)
    osw.write("%s\r\n\r\n" format response)
    osw.flush()
    socket.close()
    server.close()
  }
}
