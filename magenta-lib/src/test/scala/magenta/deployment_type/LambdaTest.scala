package magenta.deployment_type

import java.io.File
import java.util.UUID

import com.amazonaws.regions.{Regions, Region}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import magenta.artifact.{S3Package, S3Path}
import magenta.fixtures._
import magenta.tasks.{S3Upload, UpdateS3Lambda}
import magenta.{App, DeployReporter, DeployTarget, DeploymentPackage, KeyRing, NamedStack, DeploymentResources, fixtures}
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

class LambdaTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: AmazonS3 = mock[AmazonS3Client]



  behavior of "Lambda deployment action uploadLambda"

  val data: Map[String, JValue] = Map(
    "bucket" -> "lambda-bucket",
    "fileName" -> "test-file.zip",
    "functionNames" -> List("MyFunction-")
  )

  val app = Seq(App("lambda"))
  val pkg = DeploymentPackage("lambda", app, data, "aws-s3-lambda", S3Package("artifact-bucket", "test/123/lambda"))
  val defaultRegion = Region.getRegion(Regions.fromName("eu-west-1"))

  it should "produce an S3 upload task" in {
    val tasks = Lambda.actions("uploadLambda")(pkg)(DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(PROD), NamedStack("test")))
    tasks should be (List(
      S3Upload(
        bucket = "lambda-bucket",
        paths = Seq(S3Path("artifact-bucket", "test/123/lambda/test-file.zip") -> s"test/PROD/lambda/test-file.zip")
      )
    ))
  }

  it should "produce a lambda update task" in {
    val tasks = Lambda.actions("updateLambda")(pkg)(DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(PROD), NamedStack("test")))
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
    val dataWithStack: Map[String, JValue] = Map(
      "bucket" -> "lambda-bucket",
      "fileName" -> "test-file.zip",
      "prefixStack" -> true,
      "functionNames" -> List("MyFunction-")
    )
    val app = Seq(App("lambda"))
    val pkg = DeploymentPackage("lambda", app, dataWithStack, "aws-s3-lambda", S3Package("artifact-bucket", "test/123/lambda"))

    val tasks = Lambda.actions("updateLambda")(pkg)(DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(PROD), NamedStack("some-stack")))
    tasks should be (List(
      UpdateS3Lambda(
        functionName = "some-stackMyFunction-PROD",
        s3Bucket = "lambda-bucket",
        s3Key = "some-stack/PROD/lambda/test-file.zip",
        region = defaultRegion
      )
    ))
  }

  it should "create an update lambda for each region" in {
    val dataWithStack: Map[String, JValue] = Map(
      "bucket" -> "lambda-bucket",
      "fileName" -> "test-file.zip",
      "prefixStack" -> true,
      "functionNames" -> List("MyFunction-"),
      "regions" -> List("us-east-1", "ap-southeast-2")
    )
    val app = Seq(App("lambda"))
    val pkg = DeploymentPackage("lambda", app, dataWithStack, "aws-s3-lambda", S3Package("artifact-bucket", "test/123/lambda"))

    val tasks = Lambda.actions("updateLambda")(pkg)(DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(PROD), NamedStack("some-stack")))
    tasks should be (List(
      UpdateS3Lambda(
        functionName = "some-stackMyFunction-PROD",
        s3Bucket = "lambda-bucket",
        s3Key = "some-stack/PROD/lambda/test-file.zip",
        region = Region.getRegion(Regions.fromName("us-east-1"))
      ),
      UpdateS3Lambda(
        functionName = "some-stackMyFunction-PROD",
        s3Bucket = "lambda-bucket",
        s3Key = "some-stack/PROD/lambda/test-file.zip",
        region = Region.getRegion(Regions.fromName("ap-southeast-2"))
      )
    ))
  }
}
