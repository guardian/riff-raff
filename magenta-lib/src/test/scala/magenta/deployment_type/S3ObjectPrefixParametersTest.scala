package magenta.deployment_type

import magenta.artifact.S3Path
import magenta.{
  App,
  DeployReporter,
  DeployTarget,
  DeploymentPackage,
  Region,
  Stack,
  fixtures
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsBoolean, JsValue}

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
