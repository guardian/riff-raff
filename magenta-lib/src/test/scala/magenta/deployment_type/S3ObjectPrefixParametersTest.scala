package magenta.deployment_type

import magenta.artifact.S3Path
import magenta.{
  App,
  DeployReporter,
  DeployTarget,
  DeploymentPackage,
  Region,
  Stack,
  Stage,
  fixtures
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsBoolean, JsObject, JsString, JsValue}

import java.util.UUID

object TestS3DeploymentType
    extends DeploymentType
    with S3ObjectPrefixParameters {
  def defaultActions = Nil

  def documentation = "n/a"

  def name = "n/a"
}

class S3ObjectPrefixParametersTest extends AnyFlatSpec with Matchers {
  val target = DeployTarget(
    fixtures.parameters(),
    Stack("interactives"),
    Region("eu-west-1")
  )

  val reporter =
    DeployReporter.rootReporterFor(UUID.randomUUID(), target.parameters)

  "The S3 upload path" should "be defaulted" in {
    val pkg = S3ObjectPrefixParametersTest.pkg(Map.empty)
    val path = TestS3DeploymentType.getPrefix(pkg, target, reporter)
    path shouldBe "interactives/PROD/file-upload"
  }

  "The S3 upload path" should "change when prefixStage is set" in {
    val pkg = S3ObjectPrefixParametersTest.pkg(
      Map(
        "prefixStage" -> JsBoolean(false)
      )
    )
    val path = TestS3DeploymentType.getPrefix(pkg, target, reporter)
    path shouldBe "interactives/file-upload"
  }

  "The S3 upload path" should "change when prefixStack is set" in {
    val pkg = S3ObjectPrefixParametersTest.pkg(
      Map(
        "prefixStack" -> JsBoolean(false)
      )
    )
    val path = TestS3DeploymentType.getPrefix(pkg, target, reporter)
    path shouldBe "PROD/file-upload"
  }

  "prefixStagePaths" should "take precedent over other prefixes" in {
    val pkg = S3ObjectPrefixParametersTest.pkg(
      Map(
        "prefixStage" -> JsBoolean(false),
        "prefixStack" -> JsBoolean(false),
        "prefixPackage" -> JsBoolean(true),
        "prefixApp" -> JsBoolean(false),
        "prefixStagePaths" -> JsObject(
          Seq(
            "CODE" -> JsString("atoms-CODE"),
            "PROD" -> JsString("atoms")
          )
        )
      )
    )
    val path = TestS3DeploymentType.getPrefix(pkg, target, reporter)
    path shouldBe "atoms"
  }

  "The S3 upload path" should "customisable via prefixStagePaths" in {
    val pkg = S3ObjectPrefixParametersTest.pkg(
      Map(
        "prefixStagePaths" -> JsObject(
          Seq(
            "CODE" -> JsString("atoms-CODE"),
            "PROD" -> JsString("atoms")
          )
        )
      )
    )

    // PROD stage deploy target
    val productionPath = TestS3DeploymentType.getPrefix(pkg, target, reporter)
    productionPath shouldBe "atoms"

    // CODE stage deploy target
    val targetStageCODE = DeployTarget(
      fixtures.parameters(stage = Stage("CODE")),
      Stack("interactives"),
      Region("eu-west-1")
    )

    val codePath =
      TestS3DeploymentType.getPrefix(pkg, targetStageCODE, reporter)
    codePath shouldBe "atoms-CODE"
  }

  "Setting prefixStagePaths" should "error when deploying to an unknown stage" in {
    val targetStageUnknown = DeployTarget(
      fixtures.parameters(stage = Stage("UAT")),
      Stack("interactives"),
      Region("eu-west-1")
    )

    val pkg = S3ObjectPrefixParametersTest.pkg(
      Map(
        "prefixStagePaths" -> JsObject(
          Map(
            "CODE" -> JsString("atoms-CODE"),
            "PROD" -> JsString("atoms")
          )
        )
      )
    )

    val thrown = the[magenta.FailException] thrownBy {
      // We expect using an stage deploy target to throw.
      TestS3DeploymentType.getPrefix(pkg, targetStageUnknown, reporter)
    }

    thrown.getMessage should equal(
      """
         |Unable to locate prefix for stage UAT.
         |
         |prefixStagePaths is set to:
         |
         |Map(CODE -> atoms-CODE, PROD -> atoms)
         |
         |To resolve, either:
         |  - Deploy to a known stage
         |  - Update prefixStagePaths, adding a value for UAT
         |""".stripMargin
    )
  }
}

object S3ObjectPrefixParametersTest {
  def pkg(params: Map[String, JsValue]): DeploymentPackage = {
    DeploymentPackage(
      name = "file-upload",
      pkgApp = App("interactives-upload"),
      pkgSpecificData = params,
      deploymentTypeName = "n/a",
      s3Package = S3Path("test", "test"),
      deploymentTypes = Seq(TestS3DeploymentType)
    )
  }
}
