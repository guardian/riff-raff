package magenta.deployment_type

import java.io.File

import magenta.{App, DeploymentPackage, KeyRing, NamedStack, SystemUser}
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL._
import org.scalatest.{FlatSpec, Matchers}
import magenta.fixtures._
import magenta.tasks.{S3Upload, UpdateS3Lambda}

class LambdaTest extends FlatSpec with Matchers {
  implicit val fakeKeyRing = KeyRing(SystemUser(None))

  behavior of "Lambda deployment action uploadLambda"

  val data: Map[String, JValue] = Map(
    "bucket" -> "lambda-bucket",
    "fileName" -> "test-file.zip",
    "functionName" -> "MyFunction-"
  )

  val app = Seq(App("lambda"))
  val pkg = DeploymentPackage("lambda", app, data, "aws-s3-lambda", new File("/tmp/packages/webapp"))

  it should "produce an S3 upload task" in {
    val tasks = Lambda.perAppActions("uploadLambda")(pkg)(lookupEmpty, parameters(PROD), NamedStack("test"))
    tasks should be (List(
      S3Upload(
        stack = NamedStack("test"),
        stage = PROD,
        bucket = "lambda-bucket",
        file = new File(pkg.srcDir, "test-file.zip"),
        prefixPackage = false,
        publicReadAcl = false
      )
    ))
  }

  it should "produce a lambda update task" in {
    val tasks = Lambda.perAppActions("updateLambda")(pkg)(lookupEmpty, parameters(PROD), NamedStack("test"))
    tasks should be (List(
      UpdateS3Lambda(
        functionName = "MyFunction-PROD",
        s3Bucket = "lambda-bucket",
        s3Key = "test/PROD/test-file.zip"
      )
    ))
  }
}
