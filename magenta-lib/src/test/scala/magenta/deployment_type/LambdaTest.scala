package magenta.deployment_type

import java.util.UUID
import magenta.artifact.S3Path
import magenta.fixtures._
import magenta.tasks.{S3Upload, UpdateS3Lambda}
import magenta.{App, DeployReporter, DeployTarget, DeploymentPackage, DeploymentResources, FailException, KeyRing, Region, Stack, fixtures}
import org.mockito.ArgumentMatchers
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.{JsBoolean, JsString, JsValue, Json}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.{GetParameterRequest, GetParameterResponse, Parameter}
import software.amazon.awssdk.services.sts.StsClient

import scala.concurrent.ExecutionContext.global

class LambdaTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  implicit val reporter: DeployReporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: S3Client = mock[S3Client]
  implicit val stsClient: StsClient = mock[StsClient]
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
    val resources = DeploymentResources(reporter, lookupEmpty, artifactClient, stsClient, global)
    val tasks = Lambda.actionsMap("uploadLambda").taskGenerator(pkg, resources, DeployTarget(parameters(PROD), Stack("test"), region))
    tasks should be (List(
      S3Upload(
        Region("eu-west-1"),
        bucket = "lambda-bucket",
        paths = Seq(S3Path("artifact-bucket", "test/123/lambda/test-file.zip") -> s"test/PROD/lambda/test-file.zip"),
      )
    ))
  }

  it should "produce a lambda update task" in {
    val tasks = Lambda.actionsMap("updateLambda").taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient, stsClient, global), DeployTarget(parameters(PROD), Stack("test"), region))
    tasks should be (List(
      UpdateS3Lambda(
        function = LambdaFunctionName("MyFunction-PROD"),
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

    val tasks = Lambda.actionsMap("updateLambda").taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient, stsClient, global), DeployTarget(parameters(PROD), Stack("some-stack"), region))
    tasks should be (List(
      UpdateS3Lambda(
        function = LambdaFunctionName("some-stackMyFunction-PROD"),
        s3Bucket = "lambda-bucket",
        s3Key = "some-stack/PROD/lambda/test-file.zip",
        region = defaultRegion
      )
    ))
  }

  it should "use tags instead of function names" in {
    val dataWithLookupByTags: Map[String, JsValue] = Map(
      "bucket" -> JsString("lambda-bucket"),
      "fileName" -> JsString("test-file.zip"),
      "lookupByTags" -> JsBoolean(true)
    )
    val app = App("lambda")
    val pkg = DeploymentPackage("lambda", app, dataWithLookupByTags, "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"), deploymentTypes)

    val tasks = Lambda.actionsMap("updateLambda").taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient, stsClient, global), DeployTarget(parameters(PROD), Stack("some-stack"), region))
    tasks should be (List(
      UpdateS3Lambda(
        function = LambdaFunctionTags(Map("Stack" -> "some-stack", "Stage" -> "PROD", "App" -> "lambda")),
        s3Bucket = "lambda-bucket",
        s3Key = "some-stack/PROD/lambda/test-file.zip",
        region = defaultRegion
      )
    ))
  }

  it should "refuse to work if a bucket name is provided and bucketSsmLookup is true" in {
    val dataWithoutStackOverride: Map[String, JsValue] = Map(
      "bucket" -> JsString("lambda-bucket"),
      "bucketSsmLookup" -> JsBoolean(true),
      "functionNames" -> Json.arr("MyFunction-")
    )
    val app = App("lambda")
    val pkg = DeploymentPackage("lambda", app, dataWithoutStackOverride, "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"), deploymentTypes)

    val e = the [FailException] thrownBy {
      Lambda.actionsMap(
        "updateLambda").taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient, stsClient, global),
        DeployTarget(parameters(PROD), Stack("some-stack"), region))
    }
    e.message shouldBe "One and only one of the following must be set: the bucket parameter or bucketSsmLookup=true"
  }

  it should "refuse to work if bucket name is not provided and bucketSsmLookup is false" in {
    val dataWithoutStackOverride: Map[String, JsValue] = Map(
      "functionNames" -> Json.arr("MyFunction-")
    )
    val app = App("lambda")
    val pkg = DeploymentPackage("lambda", app, dataWithoutStackOverride, "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"), deploymentTypes)

    val e = the [FailException] thrownBy {
      Lambda.actionsMap(
        "updateLambda").taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient, stsClient, global),
        DeployTarget(parameters(PROD), Stack("some-stack"), region))
    }
    e.message shouldBe "One and only one of the following must be set: the bucket parameter or bucketSsmLookup=true"
  }

  it should "lookup bucket from SSM when bucketSsmLookup is true" in {
    val dataWithoutStackOverride: Map[String, JsValue] = Map(
      "bucketSsmLookup" -> JsBoolean(true),
      "functionNames" -> Json.arr("MyFunction-")
    )
    val app = App("lambda")
    val pkg = DeploymentPackage("lambda", app, dataWithoutStackOverride, "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"), deploymentTypes)

    val ssmClient = mock[SsmClient]

    when(ssmClient.getParameter(ArgumentMatchers.any(classOf[GetParameterRequest]))).thenReturn(
      GetParameterResponse.builder.parameter(Parameter.builder.value("bobbins").build).build
    )
    object LambdaTest extends Lambda {
      override def withSsm[T](keyRing: KeyRing, region: Region, resources: DeploymentResources): (SsmClient => T) => T = _(ssmClient)
    }

    val tasks = LambdaTest.actionsMap("updateLambda")
      .taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient, stsClient, global),
        DeployTarget(parameters(PROD), Stack("some-stack"), region)
      )

    tasks shouldBe List(
      UpdateS3Lambda(
        function = LambdaFunctionName("some-stackMyFunction-PROD"),
        s3Bucket = s"bobbins",
        s3Key = "some-stack/PROD/lambda/lambda.zip",
        region = defaultRegion
      )
    )

  }

  it should "omit prefix when prefixStackToKeyParam is set to false" in {
    val dataWithLookupByTags: Map[String, JsValue] = Map(
      "bucket" -> JsString("lambda-bucket"),
      "fileName" -> JsString("test-file.zip"),
      "lookupByTags" -> JsBoolean(true),
      "prefixStackToKey" -> JsBoolean(false)
    )
    val app = App("lambda")
    val pkg = DeploymentPackage("lambda", app, dataWithLookupByTags, "aws-lambda",
      S3Path("artifact-bucket", "test/123/lambda"), deploymentTypes)

    val tasks = Lambda.actionsMap("updateLambda").taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient, stsClient, global), DeployTarget(parameters(PROD), Stack("some-stack"), region))
    tasks should be (List(
      UpdateS3Lambda(
        function = LambdaFunctionTags(Map("Stack" -> "some-stack", "Stage" -> "PROD", "App" -> "lambda")),
        s3Bucket = "lambda-bucket",
        s3Key = "PROD/lambda/test-file.zip",
        region = defaultRegion
      )
    ))
  }

}
