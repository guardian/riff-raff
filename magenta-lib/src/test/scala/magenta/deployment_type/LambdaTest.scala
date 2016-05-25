package magenta.deployment_type

import java.io.File
import java.util.UUID

import magenta.{App, DeployReporter, DeploymentPackage, KeyRing, NamedStack, SystemUser, fixtures}
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL._
import org.scalatest.{FlatSpec, Matchers}
import magenta.fixtures._
import magenta.tasks.{S3Upload, UpdateS3Lambda}

class LambdaTest extends FlatSpec with Matchers {
  implicit val fakeKeyRing = KeyRing(SystemUser(None))
  implicit val logger = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())

  behavior of "Lambda deployment action uploadLambda"

  val data: Map[String, JValue] = Map(
    "bucket" -> "lambda-bucket",
    "fileName" -> "test-file.zip",
    "functionNames" -> List("MyFunction-")
  )

  val app = Seq(App("lambda"))
  val pkg = DeploymentPackage("lambda", app, data, "aws-s3-lambda", new File("/tmp/packages/webapp"))

  it should "produce an S3 upload task" in {
    val tasks = Lambda.perAppActions("uploadLambda")(pkg)(logger, lookupEmpty, parameters(PROD), NamedStack("test"))
    tasks should be (List(
      S3Upload(
        bucket = "lambda-bucket",
        files = Seq(new File(pkg.srcDir, "test-file.zip") -> s"test/PROD/lambda/test-file.zip")
      )
    ))
  }

  it should "produce a lambda update task" in {
    val tasks = Lambda.perAppActions("updateLambda")(pkg)(logger, lookupEmpty, parameters(PROD), NamedStack("test"))
    tasks should be (List(
      UpdateS3Lambda(
        functionName = "MyFunction-PROD",
        s3Bucket = "lambda-bucket",
        s3Key = "test/PROD/lambda/test-file.zip"
      )
    ))
  }
}
