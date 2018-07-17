package magenta.deployment_type

import java.util.UUID

import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import magenta.artifact.S3Path
import magenta.fixtures._
import magenta.tasks.{S3Upload, UpdateS3Lambda}
import magenta.{App, DeployReporter, DeployTarget, DeploymentPackage, DeploymentResources, KeyRing, Stack, Region, fixtures}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.{JsBoolean, JsString, JsValue, Json}

class LambdaTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: AmazonS3 = mock[AmazonS3Client]
  val region = Region("eu-west-1")
  val deploymentTypes = Seq(Lambda)

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

    val tasks = Lambda.actionsMap("updateLambda").taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(PROD), Stack("some-stack"), region))
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

    val tasks = Lambda.actionsMap("updateLambda").taskGenerator(pkg, DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(PROD), Stack("some-stack"), region))
    tasks should be (List(
      UpdateS3Lambda(
        function = LambdaFunctionTags(Map("Stack" -> "some-stack", "Stage" -> "PROD", "App" -> "lambda")),
        s3Bucket = "lambda-bucket",
        s3Key = "some-stack/PROD/lambda/test-file.zip",
        region = defaultRegion
      )
    ))
  }

}
