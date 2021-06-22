package magenta.deployment_type

import magenta.Strategy.MostlyHarmless

import java.util.UUID
import magenta._
import magenta.artifact.S3Path
import magenta.deployment_type.param_reads.PatternValue
import magenta.fixtures._
import magenta.tasks.{S3Upload, UpdateS3Lambda}
import org.mockito.Mockito._
import org.mockito.MockitoSugar
import org.scalatest.Inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsBoolean, JsString, JsValue, Json}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient

import scala.concurrent.ExecutionContext.global

class DeploymentTypeTest
    extends AnyFlatSpec
    with Matchers
    with Inside
    with MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter =
    DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: S3Client = null
  implicit val stsClient: StsClient = null
  val region = Region("eu-west-1")
  val deploymentTypes = Seq(S3, LambdaDeploy)

  "Deployment types" should "automatically register params in the params Seq" in {
    S3.params should have size 11
    S3.params.map(_.name).toSet should be(
      Set(
        "prefixStage",
        "prefixPackage",
        "prefixStack",
        "prefixApp",
        "pathPrefixResource",
        "bucket",
        "publicReadAcl",
        "bucketResource",
        "cacheControl",
        "surrogateControl",
        "mimeTypes"
      )
    )
  }

  private val sourceS3Package =
    S3Path("artifact-bucket", "test/123/static-files")

  private val defaultRegion = Region("eu-west-1")

  it should "throw a NoSuchElementException if a required parameter is missing" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("bucket-1234")
    )

    val p = DeploymentPackage(
      "myapp",
      app1,
      data,
      "aws-s3",
      sourceS3Package,
      deploymentTypes
    )

    val thrown = the[NoSuchElementException] thrownBy {
      S3.actionsMap("uploadStaticFiles")
        .taskGenerator(
          p,
          DeploymentResources(
            reporter,
            lookupSingleHost,
            artifactClient,
            stsClient,
            global
          ),
          DeployTarget(parameters(CODE), stack, region)
        ) should be(
        List(
          S3Upload(
            Region("eu-west-1"),
            "bucket-1234",
            Seq(sourceS3Package -> "CODE/myapp"),
            List(PatternValue(".*", "no-cache"))
          )
        )
      )
    }

    thrown.getMessage should equal(
      "Package myapp [aws-s3] requires parameter cacheControl of type List"
    )
  }

  it should "log a warning when a default is explicitly set" in {
    val mockReporter = mock[DeployReporter]

    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("bucket-1234"),
      "cacheControl" -> JsString("monkey"),
      "prefixStage" -> JsBoolean(true),
      "publicReadAcl" -> JsBoolean(true)
    )

    val p = DeploymentPackage(
      "myapp",
      app1,
      data,
      "aws-s3",
      sourceS3Package,
      deploymentTypes
    )

    S3.actionsMap("uploadStaticFiles")
      .taskGenerator(
        p,
        DeploymentResources(
          mockReporter,
          lookupSingleHost,
          artifactClient,
          stsClient,
          global
        ),
        DeployTarget(parameters(CODE), stack, region)
      )

    verify(mockReporter).warning(
      "Parameter prefixStage is unnecessarily explicitly set to the default value of true"
    )
  }

  "Amazon Web Services S3" should "have a uploadStaticFiles action" in {

    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("bucket-1234"),
      "cacheControl" -> JsString("no-cache"),
      "surrogateControl" -> JsString("max-age=3600"),
      "publicReadAcl" -> JsBoolean(true)
    )

    val p = DeploymentPackage(
      "myapp",
      app1,
      data,
      "aws-s3",
      sourceS3Package,
      deploymentTypes
    )

    S3.actionsMap("uploadStaticFiles")
      .taskGenerator(
        p,
        DeploymentResources(
          reporter,
          lookupSingleHost,
          artifactClient,
          stsClient,
          global
        ),
        DeployTarget(parameters(CODE), stack, region)
      ) should be(
      List(
        S3Upload(
          Region("eu-west-1"),
          "bucket-1234",
          Seq(sourceS3Package -> "test-stack/CODE/myapp"),
          cacheControlPatterns = List(PatternValue(".*", "no-cache")),
          surrogateControlPatterns = List(PatternValue(".*", "max-age=3600")),
          publicReadAcl = true
        )
      )
    )
  }

  it should "take a pattern list for cache control" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("bucket-1234"),
      "cacheControl" -> Json.arr(
        Json.obj("pattern" -> "^sub", "value" -> "no-cache"),
        Json.obj("pattern" -> ".*", "value" -> "public; max-age:3600")
      ),
      "publicReadAcl" -> JsBoolean(true)
    )

    val p = DeploymentPackage(
      "myapp",
      app1,
      data,
      "aws-s3",
      sourceS3Package,
      deploymentTypes
    )

    inside(
      S3.actionsMap("uploadStaticFiles")
        .taskGenerator(
          p,
          DeploymentResources(
            reporter,
            lookupSingleHost,
            artifactClient,
            stsClient,
            global
          ),
          DeployTarget(parameters(CODE), stack, region)
        )
        .head
    ) { case upload: S3Upload =>
      upload.cacheControlPatterns should be(
        List(
          PatternValue("^sub", "no-cache"),
          PatternValue(".*", "public; max-age:3600")
        )
      )
    }
  }

  it should "allow the path to be varied by Deployment Resource lookup" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("bucket-1234"),
      "cacheControl" -> JsString("no-cache"),
      "pathPrefixResource" -> JsString("s3-path-prefix"),
      "prefixStage" -> JsBoolean(
        false
      ), // when we are using pathPrefixResource, we generally don't need or want the stage prefixe - we're already varying based on stage
      "prefixPackage" -> JsBoolean(false),
      "publicReadAcl" -> JsBoolean(true)
    )

    val p = DeploymentPackage(
      "myapp",
      app1,
      data,
      "aws-s3",
      sourceS3Package,
      deploymentTypes
    )

    val lookup = stubLookup(
      List(Host("the_host", app1, stage = CODE.name, stack.name)),
      Map(
        "s3-path-prefix" -> Seq(
          Datum(
            stack.name,
            app1.name,
            CODE.name,
            "testing/2016/05/brexit-companion",
            None
          )
        )
      )
    )

    inside(
      S3.actionsMap("uploadStaticFiles")
        .taskGenerator(
          p,
          DeploymentResources(
            reporter,
            lookup,
            artifactClient,
            stsClient,
            global
          ),
          DeployTarget(parameters(CODE), stack, region)
        )
        .head
    ) { case upload: S3Upload =>
      upload.paths should be(
        Seq(sourceS3Package -> "testing/2016/05/brexit-companion")
      )
    }
  }

  "AWS Lambda" should "have a updateLambda action" in {

    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("artifact-bucket"),
      "functions" -> Json.obj(
        "CODE" -> Json.obj(
          "name" -> "myLambda"
        )
      )
    )

    val p = DeploymentPackage(
      "myapp",
      app1,
      data,
      "aws-lambda",
      S3Path("artifact-bucket", "test/123"),
      deploymentTypes
    )

    LambdaDeploy
      .actionsMap("updateLambda")
      .taskGenerator(
        p,
        DeploymentResources(
          reporter,
          lookupSingleHost,
          artifactClient,
          stsClient,
          global
        ),
        DeployTarget(parameters(CODE), stack, region)
      ) should be(
      List(
        UpdateS3Lambda(
          LambdaFunctionName("myLambda"),
          "artifact-bucket",
          "test-stack/CODE/the_role/lambda.zip",
          defaultRegion
        )
      )
    )
  }

  it should "throw an exception if a required mapping is missing" in {
    val badData: Map[String, JsValue] = Map(
      "bucket" -> JsString("artifact-bucket"),
      "functions" -> Json.obj(
        "BADSTAGE" -> Json.obj(
          "name" -> "myLambda"
        )
      )
    )

    val p = DeploymentPackage(
      "myapp",
      app1,
      badData,
      "aws-lambda",
      S3Path("artifact-bucket", "test/123"),
      deploymentTypes
    )

    val thrown = the[FailException] thrownBy {
      LambdaDeploy
        .actionsMap("updateLambda")
        .taskGenerator(
          p,
          DeploymentResources(
            reporter,
            lookupSingleHost,
            artifactClient,
            stsClient,
            global
          ),
          DeployTarget(parameters(CODE), stack, region)
        ) should be(
        List(
          UpdateS3Lambda(
            LambdaFunctionName("myLambda"),
            "artifact-bucket",
            "test-stack/CODE/the_role/lambda.zip",
            defaultRegion
          )
        )
      )
    }

    thrown.getMessage should equal("Function not defined for stage CODE")
  }

  def parameters(stage: Stage) = DeployParameters(
    Deployer("tester"),
    Build("project", "version"),
    stage,
    updateStrategy = MostlyHarmless
  )
}
