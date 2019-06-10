package magenta.deployment_type

import java.util.UUID

import magenta.artifact.S3Path
import magenta.fixtures._
import magenta.tasks.{S3Upload, UpdateS3Lambda}
import magenta.{App, DeployReporter, DeployTarget, DeploymentPackage, DeploymentResources, FailException, KeyRing, Region, Stack, fixtures}
import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito._
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.{JsBoolean, JsString, JsValue, Json}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{Bucket, ListBucketsResponse}
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse

class LambdaTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  implicit val reporter: DeployReporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: S3Client = mock[S3Client]
  val region = Region("eu-west-1")
  val deploymentTypes: Seq[Lambda.type] = Seq(Lambda)

  behavior of "Lambda deployment action uploadLambda"

  val data: Map[String, JsValue] = Map(
    "bucket" -> JsString("lambda-bucket"),
    "fileName" -> JsString("test-file.zip"),
    "prefixStack" -> JsBoolean(false),
    "functionNames" -> Json.arr("MyFunction-")
  )

  val app = App("lambda")
  val pkg = DeploymentPackage("lambda", app, data, "aws-lambda", S3Path("artifact-bucket", "test/123/lambda"),
    deploymentTypes)
  val defaultRegion = Region("eu-west-1")

  it should "produce an S3 upload task" in {
    val tasks = Lambda.actionsMap("uploadLambda").taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(PROD), Stack("test"), region))
    tasks should be (List(
      S3Upload(
        Region("eu-west-1"),
        bucket = "lambda-bucket",
        paths = Seq(S3Path("artifact-bucket", "test/123/lambda/test-file.zip") -> s"test/PROD/lambda/test-file.zip")
      )
    ))
  }

  it should "produce a lambda update task" in {
    val tasks = Lambda.actionsMap("updateLambda").taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(PROD), Stack("test"), region))
    tasks should be (List(
      UpdateS3Lambda(
        functionName = "MyFunction-PROD",
        s3Bucket = "lambda-bucket",
        s3Key = "test/PROD/lambda/test-file.zip",
        region = defaultRegion
      )
    ))
  }

  it should "prefix stack name to function name" in {
    val dataWithoutStackOverride: Map[String, JsValue] = Map(
      "bucket" -> JsString("lambda-bucket"),
      "fileName" -> JsString("test-file.zip"),
      "functionNames" -> Json.arr("MyFunction-")
    )
    val app = App("lambda")
    val pkg = DeploymentPackage("lambda", app, dataWithoutStackOverride, "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"), deploymentTypes)

    val tasks = Lambda.actionsMap("updateLambda").taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(PROD), Stack("some-stack"), region))
    tasks should be (List(
      UpdateS3Lambda(
        functionName = "some-stackMyFunction-PROD",
        s3Bucket = "lambda-bucket",
        s3Key = "some-stack/PROD/lambda/test-file.zip",
        region = defaultRegion
      )
    ))
  }

  it should "refuse to work if a bucket name is provided and autoDistBucket is true" in {
    val dataWithoutStackOverride: Map[String, JsValue] = Map(
      "bucket" -> JsString("lambda-bucket"),
      "autoDistBucket" -> JsBoolean(true),
      "functionNames" -> Json.arr("MyFunction-")
    )
    val app = App("lambda")
    val pkg = DeploymentPackage("lambda", app, dataWithoutStackOverride, "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"), deploymentTypes)

    val e = the [FailException] thrownBy {
      Lambda.actionsMap(
        "updateLambda").taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient),
        DeployTarget(parameters(PROD), Stack("some-stack"), region))
    }
    e.message shouldBe "One and only one of the following must be set: the bucket parameter or autoDistBucket=true"
  }

  it should "refuse to work if bucket name is not provided and autoDistBucket is false" in {
    val dataWithoutStackOverride: Map[String, JsValue] = Map(
      "functionNames" -> Json.arr("MyFunction-")
    )
    val app = App("lambda")
    val pkg = DeploymentPackage("lambda", app, dataWithoutStackOverride, "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"), deploymentTypes)

    val e = the [FailException] thrownBy {
      Lambda.actionsMap(
        "updateLambda").taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient),
        DeployTarget(parameters(PROD), Stack("some-stack"), region))
    }
    e.message shouldBe "One and only one of the following must be set: the bucket parameter or autoDistBucket=true"
  }

  it should "use an automatically generate bucket when autoDistBucket is true" in {
    val dataWithoutStackOverride: Map[String, JsValue] = Map(
      "autoDistBucket" -> JsBoolean(true),
      "functionNames" -> Json.arr("MyFunction-")
    )
    val app = App("lambda")
    val pkg = DeploymentPackage("lambda", app, dataWithoutStackOverride, "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"), deploymentTypes)

    val s3Client = mock[S3Client]
    val stsClient = mock[StsClient]
    when(stsClient.getCallerIdentity()).thenReturn(GetCallerIdentityResponse.builder.account("123456789").build)
    when(s3Client.listBuckets()).thenReturn(
      ListBucketsResponse.builder.buckets(
        Bucket.builder.name("bucket-1").build,
        Bucket.builder.name(s"${AutoDistBucket.BUCKET_PREFIX}-123456789-${region.name}").build,
        Bucket.builder.name("bucket-3").build
      ).build
    )
    object LambdaTest extends Lambda {
      override def makeS3(keyRing: KeyRing, region: Region): S3Client = s3Client
      override def makeSTS(keyRing: KeyRing, region: Region): StsClient = stsClient
    }

    val tasks = LambdaTest.actionsMap("updateLambda")
      .taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient),
        DeployTarget(parameters(PROD), Stack("some-stack"), region)
      )

    tasks shouldBe List(
      UpdateS3Lambda(
        functionName = "some-stackMyFunction-PROD",
        s3Bucket = s"${AutoDistBucket.BUCKET_PREFIX}-123456789-${region.name}",
        s3Key = "some-stack/PROD/lambda/lambda.zip",
        region = defaultRegion
      )
    )

  }

}
