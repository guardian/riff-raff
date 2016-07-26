package magenta.deployment_type

import java.io.File
import java.util.UUID

import magenta.fixtures._
import magenta.tasks.{S3Upload, UpdateS3Lambda}
import magenta.{App, DeployReporter, DeployTarget, DeploymentPackage, KeyRing, NamedStack, DeploymentResources, fixtures}
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL._
import org.scalatest.{FlatSpec, Matchers}

class LambdaTest extends FlatSpec with Matchers {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())

  behavior of "Lambda deployment action uploadLambda"

  val data: Map[String, JValue] = Map(
    "bucket" -> "lambda-bucket",
    "fileName" -> "test-file.zip",
    "functionNames" -> List("MyFunction-")
  )

  val app = Seq(App("lambda"))
  val pkg = DeploymentPackage("lambda", app, data, "aws-s3-lambda", new File("/tmp/packages/webapp"))

  it should "produce an S3 upload task" in {
    val tasks = Lambda.perAppActions("uploadLambda")(pkg)(DeploymentResources(reporter, lookupEmpty), DeployTarget(parameters(PROD), NamedStack("test")))
    tasks should be (List(
      S3Upload(
        bucket = "lambda-bucket",
        files = Seq(new File(pkg.srcDir, "test-file.zip") -> s"test/PROD/lambda/test-file.zip")
      )
    ))
  }

  it should "produce a lambda update task" in {
    val tasks = Lambda.perAppActions("updateLambda")(pkg)(DeploymentResources(reporter, lookupEmpty), DeployTarget(parameters(PROD), NamedStack("test")))
    tasks should be (List(
      UpdateS3Lambda(
        functionName = "MyFunction-PROD",
        s3Bucket = "lambda-bucket",
        s3Key = "test/PROD/lambda/test-file.zip"
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
    val pkg = DeploymentPackage("lambda", app, dataWithStack, "aws-s3-lambda", new File("/tmp/packages/webapp"))

    val tasks = Lambda.perAppActions("updateLambda")(pkg)(DeploymentResources(reporter, lookupEmpty), DeployTarget(parameters(PROD), NamedStack("some-stack")))
    tasks should be (List(
      UpdateS3Lambda(
        functionName = "some-stackMyFunction-PROD",
        s3Bucket = "lambda-bucket",
        s3Key = "some-stack/PROD/lambda/test-file.zip"
      )
    ))
  }
}
