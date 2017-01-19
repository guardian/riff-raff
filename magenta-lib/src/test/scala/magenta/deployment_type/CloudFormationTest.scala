package magenta.deployment_type

import java.util.UUID

import com.amazonaws.regions.RegionUtils
import com.amazonaws.services.s3.AmazonS3
import magenta._
import magenta.artifact.S3Path
import magenta.fixtures._
import magenta.tasks.CloudFormation.{SpecifiedValue, UseExistingValue}
import magenta.tasks.UpdateCloudFormationTask._
import magenta.tasks._
import org.scalatest.{FlatSpec, Inside, Matchers}
import play.api.libs.json.JsValue

class CloudFormationTest extends FlatSpec with Matchers with Inside {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: AmazonS3 = null
  val region = Region("eu-west-1")
  val deploymentTypes = Seq(CloudFormation)

  "cloudformation deployment type" should "have an updateStack action" in {
    val data: Map[String, JsValue] = Map()
    val app = Seq(App("app"))
    val stack = NamedStack("cfn")
    val cfnStackName = s"cfn-app-PROD"
    val p = DeploymentPackage("app", app, data, "cloud-formation", S3Path("artifact-bucket", "test/123"), true,
      deploymentTypes)

    inside(CloudFormation.actionsMap("updateStack").taskGenerator(p, DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(), stack, region))) {
      case List(updateTask, checkTask) =>
        inside(updateTask) {
          case UpdateCloudFormationTask(taskRegion, stackName, path, userParams, amiParamTags, _, stage, stack, ifAbsent, alwaysUpload) =>
            taskRegion should be(region)
            stackName should be(LookupByName(cfnStackName))
            path should be(S3Path("artifact-bucket", "test/123/cloud-formation/cfn.json"))
            userParams should be(Map.empty)
            amiParamTags should be(Map.empty)
            stage should be(PROD)
            stack should be(NamedStack("cfn"))
            ifAbsent should be(true)
            alwaysUpload shouldBe false
        }
        inside(checkTask) {
          case CheckUpdateEventsTask(taskRegion, updateStackName) =>
            taskRegion should be(region)
            updateStackName should be(LookupByName(cfnStackName))
        }
    }
  }

  "UpdateCloudFormationTask" should "substitute stack and stage parameters" in {
    val templateParameters =
      Seq(TemplateParameter("param1", false), TemplateParameter("Stack", false), TemplateParameter("Stage", false))
    val combined = UpdateCloudFormationTask.combineParameters(NamedStack("cfn"), PROD, templateParameters, Map("param1" -> "value1"))

    combined should be(Map(
      "param1" -> SpecifiedValue("value1"),
      "Stack" -> SpecifiedValue("cfn"),
      "Stage" -> SpecifiedValue("PROD")
      ))
  }

  it should "default required parameters to use existing parameters" in {
    val templateParameters =
      Seq(TemplateParameter("param1", true), TemplateParameter("param3", false), TemplateParameter("Stage", false))
    val combined = UpdateCloudFormationTask.combineParameters(NamedStack("cfn"), PROD, templateParameters, Map("param1" -> "value1"))

    combined should be(Map(
      "param1" -> SpecifiedValue("value1"),
      "param3" -> UseExistingValue,
      "Stage" -> SpecifiedValue(PROD.name)
    ))
  }

  it should "create new CFN stack names" in {
    import UpdateCloudFormationTask.nameToCallNewStack
    nameToCallNewStack(LookupByName("name-of-stack")) shouldBe "name-of-stack"
    nameToCallNewStack(LookupByTags(Map("Stack" -> "stackName", "App" -> "appName", "Stage" -> "STAGE"))) shouldBe
      "stackName-STAGE-appName"
    nameToCallNewStack(LookupByTags(Map("Stack" -> "stackName", "App" -> "appName", "Stage" -> "STAGE", "Extra" -> "extraBit"))) shouldBe
      "stackName-STAGE-appName-extraBit"
  }

  "CloudFormationStackLookupStrategy" should "correctly create a LookupByName from deploy parameters" in {
    LookupByName(NamedStack("cfn"), Stage("STAGE"), "stackname", prependStack = true, appendStage = true) shouldBe
      LookupByName("cfn-stackname-STAGE")
    LookupByName(NamedStack("cfn"), Stage("STAGE"), "stackname", prependStack = false, appendStage = true) shouldBe
      LookupByName("stackname-STAGE")
    LookupByName(NamedStack("cfn"), Stage("STAGE"), "stackname", prependStack = false, appendStage = false) shouldBe
      LookupByName("stackname")
  }

  it should "correctly create a LookupByTags from deploy parameters" in {
    val data: Map[String, JsValue] = Map()
    val app = Seq(App("app"))
    val stack = NamedStack("cfn")
    val cfnStackName = s"cfn-app-PROD"
    val pkg = DeploymentPackage("app", app, data, "cloud-formation", S3Path("artifact-bucket", "test/123"), true,
      deploymentTypes)
    val target = DeployTarget(parameters(), stack, region)
    LookupByTags(pkg, target, reporter) shouldBe LookupByTags(Map("Stack" -> "cfn", "Stage" -> "PROD", "App" -> "app"))
  }
}
