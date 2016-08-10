package magenta

import java.io.File
import java.util.UUID

import com.amazonaws.services.s3.AmazonS3
import magenta.artifact.{S3Package, S3Path}
import magenta.deployment_type.{Lambda, PatternValue, S3}
import magenta.fixtures._
import magenta.tasks._
import org.json4s.JsonDSL._
import org.json4s._
import org.scalatest.{FlatSpec, Inside, Matchers}

class DeploymentTypeTest extends FlatSpec with Matchers with Inside {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: AmazonS3 = null

  "Deployment types" should "automatically register params in the params Seq" in {
    S3.params should have size 10
    S3.params.map(_.name).toSet should be(Set("prefixStage","prefixPackage","prefixStack", "pathPrefixResource","bucket","publicReadAcl","bucketResource","cacheControl","mimeTypes","headers"))
  }

  private val sourceS3Package = S3Package("artifact-bucket", "test/123/static-files")

  it should "throw a NoSuchElementException if a required parameter is missing" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "bucket-1234"
    )

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-s3", sourceS3Package)

    val thrown = the[NoSuchElementException] thrownBy {
      S3.perAppActions("uploadStaticFiles")(p)(DeploymentResources(reporter, lookupSingleHost, artifactClient), DeployTarget(parameters(CODE), UnnamedStack)) should be (
        List(S3Upload(
          "bucket-1234",
          Seq(sourceS3Package -> "CODE/myapp"),
          List(PatternValue(".*", "no-cache"))
        ))
      )
    }

    thrown.getMessage should equal ("Package myapp [aws-s3] requires parameter cacheControl of type List")
  }

  "Amazon Web Services S3" should "have a uploadStaticFiles action" in {

    val data: Map[String, JValue] = Map(
      "bucket" -> "bucket-1234",
      "cacheControl" -> "no-cache"
    )

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-s3", sourceS3Package)

    S3.perAppActions("uploadStaticFiles")(p)(DeploymentResources(reporter, lookupSingleHost, artifactClient), DeployTarget(parameters(CODE), UnnamedStack)) should be (
      List(S3Upload(
        "bucket-1234",
        Seq(sourceS3Package -> "CODE/myapp"),
        List(PatternValue(".*", "no-cache")),
        publicReadAcl = true
      ))
    )
  }

  it should "take a pattern list for cache control" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "bucket-1234",
      "cacheControl" -> JArray(List(
        JObject(List(JField("pattern", JString("^sub")), JField("value", JString("no-cache")))),
        JObject(List(JField("pattern", JString(".*")), JField("value", JString("public; max-age:3600"))))
      ))
    )

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-s3", sourceS3Package)

    inside(S3.perAppActions("uploadStaticFiles")(p)(DeploymentResources(reporter, lookupSingleHost, artifactClient), DeployTarget(parameters(CODE), UnnamedStack)).head) {
      case upload: S3Upload => upload.cacheControlPatterns should be(List(PatternValue("^sub", "no-cache"), PatternValue(".*", "public; max-age:3600")))
    }
  }

  it should "allow the path to be varied by Deployment Resource lookup" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "bucket-1234",
      "cacheControl" -> "no-cache",
      "pathPrefixResource" -> "s3-path-prefix",
      "prefixStage" -> false, // when we are using pathPrefixResource, we generally don't need or want the stage prefixe - we're already varying based on stage
      "prefixPackage" -> false
    )

    val p = DeploymentPackage("myapp", Seq(app1), data, "aws-s3", sourceS3Package)

    val lookup = stubLookup(List(Host("the_host", stage=CODE.name).app(app1)), Map("s3-path-prefix" -> Seq(Datum(None, app1.name, CODE.name, "testing/2016/05/brexit-companion", None))))

    inside(S3.perAppActions("uploadStaticFiles")(p)(DeploymentResources(reporter, lookup, artifactClient), DeployTarget(parameters(CODE), UnnamedStack)).head) {
      case upload: S3Upload => upload.paths should be(Seq(sourceS3Package -> "testing/2016/05/brexit-companion"))
    }
  }

  "AWS Lambda" should "have a updateLambda action" in {

    val data: Map[String, JValue] = Map(
      "functions" ->(
        "CODE" -> {
          "name" -> "myLambda"
        }
        )
    )

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-lambda", S3Package("artifact-bucket", "test/123"))

    Lambda.perAppActions("updateLambda")(p)(DeploymentResources(reporter, lookupSingleHost, artifactClient), DeployTarget(parameters(CODE), UnnamedStack)) should be (
      List(UpdateLambda(S3Path("artifact-bucket","test/123/lambda.zip"), "myLambda")
      ))
  }

  it should "throw an exception if a required mapping is missing" in {
    val badData: Map[String, JValue] = Map(
      "functions" ->(
        "BADSTAGE" -> {
          "name" -> "myLambda"
        }
        )
    )

    val p = DeploymentPackage("myapp", Seq.empty, badData, "aws-lambda", S3Package("artifact-bucket", "test/123"))

    val thrown = the[FailException] thrownBy {
      Lambda.perAppActions("updateLambda")(p)(DeploymentResources(reporter, lookupSingleHost, artifactClient), DeployTarget(parameters(CODE), UnnamedStack)) should be (
        List(UpdateLambda(S3Path("artifact-bucket","test/123/lambda.zip"), "myLambda")
        ))
    }

    thrown.getMessage should equal ("Function not defined for stage CODE")
  }

  def parameters(stage: Stage) = DeployParameters(Deployer("tester"), Build("project", "version"), stage)
}
