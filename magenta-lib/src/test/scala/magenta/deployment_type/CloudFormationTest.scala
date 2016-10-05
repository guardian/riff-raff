package magenta.deployment_type

import java.util.UUID

import com.amazonaws.regions.RegionUtils
import com.amazonaws.services.s3.AmazonS3
import magenta._
import magenta.artifact.S3Path
import magenta.fixtures._
import magenta.tasks.UpdateCloudFormationTask._
import magenta.tasks._
import org.scalatest.{FlatSpec, Inside, Matchers}
import play.api.libs.json.JsValue

class CloudFormationTest extends FlatSpec with Matchers with Inside {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: AmazonS3 = null
  val region = Region("eu-west-1")

  "cloudformation deployment type" should "have an updateStack action" in {
    val data: Map[String, JsValue] = Map()
    val app = Seq(App("app"))
    val stack = NamedStack("cfn")
    val cfnStackName = s"cfn-app-PROD"
    val p = DeploymentPackage("app", app, data, "cloudformation", S3Path("artifact-bucket", "test/123"), true)

    inside(CloudFormation.actions("updateStack")(p)(DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(), stack, region))) {
      case List(updateTask, checkTask) =>
        inside(updateTask) {
          case UpdateCloudFormationTask(stackName, path, userParams, amiParam, amiTags, _, stage, stack, ifAbsent) =>
            stackName should be(cfnStackName)
            path should be(S3Path("artifact-bucket", "test/123/cloud-formation/cfn.json"))
            userParams should be(Map.empty)
            amiParam should be("AMI")
            amiTags should be(Map.empty)
            stage should be(PROD)
            stack should be(NamedStack("cfn"))
            ifAbsent should be(true)
        }
        inside(checkTask) {
          case CheckUpdateEventsTask(updateStackName) =>
            updateStackName should be(cfnStackName)
        }
    }
  }

  "UpdateCloudFormationTask" should "substitute stack and stage parameters" in {
    val templateParameters =
      Seq(TemplateParameter("param1", false), TemplateParameter("Stack", false), TemplateParameter("Stage", false))
    val combined = UpdateCloudFormationTask.combineParameters(NamedStack("cfn"), PROD, templateParameters, Map("param1" -> "value1"), None)

    combined should be(Map(
      "param1" -> SpecifiedValue("value1"),
      "Stack" -> SpecifiedValue("cfn"),
      "Stage" -> SpecifiedValue("PROD")
      ))
  }

  it should "default required parameters to use existing parameters" in {
    val templateParameters =
      Seq(TemplateParameter("param1", true), TemplateParameter("param3", false), TemplateParameter("Stage", false))
    val combined = UpdateCloudFormationTask.combineParameters(NamedStack("cfn"), PROD, templateParameters, Map("param1" -> "value1"), None)

    combined should be(Map(
      "param1" -> SpecifiedValue("value1"),
      "param3" -> UseExistingValue,
      "Stage" -> SpecifiedValue(PROD.name)
    ))
  }
}
