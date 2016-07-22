package magenta

import java.io.File
import java.util.UUID

import magenta.deployment_type.{Lambda, PatternValue, S3}
import magenta.fixtures._
import magenta.tasks._
import org.json4s.JsonDSL._
import org.json4s._
import org.scalatest.{FlatSpec, Inside, Matchers}

class DeploymentTypeTest extends FlatSpec with Matchers with Inside {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())

  "Deployment types" should "automatically register params in the params Seq" in {
    S3.params should have size 9
    S3.params.map(_.name).toSet should be(Set("prefixStage","prefixPackage","prefixStack", "pathPrefixResource","bucket","publicReadAcl","bucketResource","cacheControl","mimeTypes"))
  }

  private val sourceFiles = new File("/tmp/packages/static-files")

  it should "throw a NoSuchElementException if a required parameter is missing" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "bucket-1234"
    )

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-s3", sourceFiles)

    val thrown = the[NoSuchElementException] thrownBy {
      S3.perAppActions("uploadStaticFiles")(p)(DeploymentResources(reporter, lookupSingleHost), DeployTarget(parameters(CODE), UnnamedStack)) should be (
        List(S3Upload(
          "bucket-1234",
          Seq(sourceFiles -> "CODE/myapp"),
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

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-s3", sourceFiles)

    S3.perAppActions("uploadStaticFiles")(p)(DeploymentResources(reporter, lookupSingleHost), DeployTarget(parameters(CODE), UnnamedStack)) should be (
      List(S3Upload(
        "bucket-1234",
        Seq(sourceFiles -> "CODE/myapp"),
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

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-s3", sourceFiles)

    inside(S3.perAppActions("uploadStaticFiles")(p)(DeploymentResources(reporter, lookupSingleHost), DeployTarget(parameters(CODE), UnnamedStack)).head) {
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

    val p = DeploymentPackage("myapp", Seq(app1), data, "aws-s3", sourceFiles)

    val lookup = stubLookup(List(Host("the_host", stage=CODE.name).app(app1)), Map("s3-path-prefix" -> Seq(Datum(None, app1.name, CODE.name, "testing/2016/05/brexit-companion", None))))

    inside(S3.perAppActions("uploadStaticFiles")(p)(DeploymentResources(reporter, lookup), DeployTarget(parameters(CODE), UnnamedStack)).head) {
      case upload: S3Upload => upload.files should be(Seq(sourceFiles -> "testing/2016/05/brexit-companion"))
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

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-lambda", new File("/tmp/packages"))

    Lambda.perAppActions("updateLambda")(p)(DeploymentResources(reporter, lookupSingleHost), DeployTarget(parameters(CODE), UnnamedStack)) should be (
      List(UpdateLambda(new File("/tmp/packages/lambda.zip"), "myLambda")
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

    val p = DeploymentPackage("myapp", Seq.empty, badData, "aws-lambda", new File("/tmp/packages"))

    val thrown = the[FailException] thrownBy {
      Lambda.perAppActions("updateLambda")(p)(DeploymentResources(reporter, lookupSingleHost), DeployTarget(parameters(CODE), UnnamedStack)) should be (
        List(UpdateLambda(new File("/tmp/packages/lambda.zip"), "myLambda")
        ))
    }

    thrown.getMessage should equal ("Function not defined for stage CODE")
  }

  def parameters(stage: Stage) = DeployParameters(Deployer("tester"), Build("project", "version"), stage)
}