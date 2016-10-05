package magenta

import java.util.UUID

import com.amazonaws.services.s3.AmazonS3
import magenta.artifact.S3Path
import magenta.deployment_type.param_reads.PatternValue
import magenta.deployment_type.{Lambda, S3}
import magenta.fixtures._
import magenta.tasks._
import org.scalatest.{FlatSpec, Inside, Matchers}
import play.api.libs.json.{JsBoolean, JsString, JsValue, Json}

class DeploymentTypeTest extends FlatSpec with Matchers with Inside {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: AmazonS3 = null
  val region = Region("eu-west-1")

  "Deployment types" should "automatically register params in the params Seq" in {
    S3.params should have size 9
    S3.params.map(_.name).toSet should be(Set("prefixStage","prefixPackage","prefixStack", "pathPrefixResource","bucket","publicReadAcl","bucketResource","cacheControl","mimeTypes"))
  }

  private val sourceS3Package = S3Path("artifact-bucket", "test/123/static-files")

  private val defaultRegion = Region("eu-west-1")

  it should "throw a NoSuchElementException if a required parameter is missing" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("bucket-1234")
    )

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-s3", sourceS3Package, true)

    val thrown = the[NoSuchElementException] thrownBy {
      S3.actions("uploadStaticFiles")(p)(DeploymentResources(reporter, lookupSingleHost, artifactClient), DeployTarget(parameters(CODE), UnnamedStack, region)) should be (
        List(S3Upload(
          Region("eu-west-1"),
          "bucket-1234",
          Seq(sourceS3Package -> "CODE/myapp"),
          List(PatternValue(".*", "no-cache"))
        ))
      )
    }

    thrown.getMessage should equal ("Package myapp [aws-s3] requires parameter cacheControl of type List")
  }

  "Amazon Web Services S3" should "have a uploadStaticFiles action" in {

    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("bucket-1234"),
      "cacheControl" -> JsString("no-cache")
    )

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-s3", sourceS3Package, true)

    S3.actions("uploadStaticFiles")(p)(DeploymentResources(reporter, lookupSingleHost, artifactClient), DeployTarget(parameters(CODE), UnnamedStack, region)) should be (
      List(S3Upload(
        Region("eu-west-1"),
        "bucket-1234",
        Seq(sourceS3Package -> "CODE/myapp"),
        List(PatternValue(".*", "no-cache")),
        publicReadAcl = true
      ))
    )
  }

  it should "take a pattern list for cache control" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("bucket-1234"),
      "cacheControl" -> Json.arr(
        Json.obj("pattern" -> "^sub", "value" -> "no-cache"),
        Json.obj("pattern" -> ".*", "value" -> "public; max-age:3600")
      )
    )

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-s3", sourceS3Package, true)

    inside(S3.actions("uploadStaticFiles")(p)(DeploymentResources(reporter, lookupSingleHost, artifactClient), DeployTarget(parameters(CODE), UnnamedStack, region)).head) {
      case upload: S3Upload => upload.cacheControlPatterns should be(List(PatternValue("^sub", "no-cache"), PatternValue(".*", "public; max-age:3600")))
    }
  }

  it should "allow the path to be varied by Deployment Resource lookup" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("bucket-1234"),
      "cacheControl" -> JsString("no-cache"),
      "pathPrefixResource" -> JsString("s3-path-prefix"),
      "prefixStage" -> JsBoolean(false), // when we are using pathPrefixResource, we generally don't need or want the stage prefixe - we're already varying based on stage
      "prefixPackage" -> JsBoolean(false)
    )

    val p = DeploymentPackage("myapp", Seq(app1), data, "aws-s3", sourceS3Package, true)

    val lookup = stubLookup(List(Host("the_host", stage=CODE.name).app(app1)), Map("s3-path-prefix" -> Seq(Datum(None, app1.name, CODE.name, "testing/2016/05/brexit-companion", None))))

    inside(S3.actions("uploadStaticFiles")(p)(DeploymentResources(reporter, lookup, artifactClient), DeployTarget(parameters(CODE), UnnamedStack, region)).head) {
      case upload: S3Upload => upload.paths should be(Seq(sourceS3Package -> "testing/2016/05/brexit-companion"))
    }
  }

  "AWS Lambda" should "have a updateLambda action" in {

    val data: Map[String, JsValue] = Map(
      "functions" -> Json.obj(
        "CODE" -> Json.obj(
          "name" -> "myLambda"
        )
      )
    )

    val p = DeploymentPackage("myapp", Seq.empty, data, "aws-lambda", S3Path("artifact-bucket", "test/123"), true)

    Lambda.actions("updateLambda")(p)(DeploymentResources(reporter, lookupSingleHost, artifactClient), DeployTarget(parameters(CODE), UnnamedStack, region)) should be (
      List(UpdateLambda(S3Path("artifact-bucket","test/123/lambda.zip"), "myLambda", defaultRegion)
      ))
  }

  it should "throw an exception if a required mapping is missing" in {
    val badData: Map[String, JsValue] = Map(
      "functions" -> Json.obj(
        "BADSTAGE" -> Json.obj(
          "name" -> "myLambda"
        )
      )
    )

    val p = DeploymentPackage("myapp", Seq.empty, badData, "aws-lambda", S3Path("artifact-bucket", "test/123"), true)

    val thrown = the[FailException] thrownBy {
      Lambda.actions("updateLambda")(p)(DeploymentResources(reporter, lookupSingleHost, artifactClient), DeployTarget(parameters(CODE), UnnamedStack, region)) should be (
        List(UpdateLambda(S3Path("artifact-bucket","test/123/lambda.zip"), "myLambda", defaultRegion)
        ))
    }

    thrown.getMessage should equal ("Function not defined for stage CODE")
  }

  def parameters(stage: Stage) = DeployParameters(Deployer("tester"), Build("project", "version"), stage)
}