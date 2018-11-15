package magenta.deployment_type

import java.util.UUID

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.cloudformation.model.{Change, ChangeSetType, Stack => CloudFormationStack}
import magenta._
import magenta.artifact.S3Path
import magenta.fixtures._
import magenta.tasks.CloudFormation.{SpecifiedValue, UseExistingValue}
import magenta.tasks.UpdateCloudFormationTask._
import magenta.tasks.{UpdateCloudFormationTask, _}
import org.scalatest.{FlatSpec, Inside, Matchers}
import play.api.libs.json.{JsBoolean, JsString, JsValue, Json}

class CloudFormationTest extends FlatSpec with Matchers with Inside {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: AmazonS3 = null
  val region = Region("eu-west-1")
  val deploymentTypes = Seq(CloudFormation)
  val app = App("app")
  val testStack = Stack("cfn")
  val cfnStackName = s"cfn-app-PROD"
  def p(data: Map[String, JsValue]) = DeploymentPackage("app", app, data, "cloud-formation", S3Path("artifact-bucket", "test/123"),
    deploymentTypes)

  private def generateTasks(data: Map[String, JsValue] = Map("cloudFormationStackByTags" -> JsBoolean(false))) = {
    val resources = DeploymentResources(reporter, lookupEmpty, artifactClient)
    CloudFormation.actionsMap("updateStack").taskGenerator(p(data), resources, DeployTarget(parameters(), testStack, region))
  }

  it should "generate the tasks in the correct order" in {
    val tasks = generateTasks()
    tasks should have size(5)

    tasks(0) shouldBe a[CreateChangeSetTask]
    tasks(1) shouldBe a[CheckChangeSetCreatedTask]
    tasks(2) shouldBe a[ExecuteChangeSetTask]
    tasks(3) shouldBe a[CheckUpdateEventsTask]
    tasks(4) shouldBe a[DeleteChangeSetTask]
  }

  it should "use the same change set name across all tasks" in {
    val tasks = generateTasks()

    val changeSetName = tasks(0).asInstanceOf[CreateChangeSetTask].changeSetName
    tasks(1).asInstanceOf[CheckChangeSetCreatedTask].changeSetName should be(changeSetName)
    tasks(2).asInstanceOf[ExecuteChangeSetTask].changeSetName should be(changeSetName)
    // check updates does not use the change set name
    tasks(4).asInstanceOf[DeleteChangeSetTask].changeSetName should be(changeSetName)
  }

  it should "ignore amiTags when amiParametersToTags and amiTags are provided" in {
    val data: Map[String, JsValue] = Map(
      "amiTags" -> Json.obj("myApp" -> JsString("fakeApp")),
      "amiParametersToTags" -> Json.obj(
        "AMI" -> Json.obj("myApp1" -> JsString("fakeApp1")),
        "RouterAMI" -> Json.obj("myApp2" -> JsString("fakeApp2"))
    ))

    val (create: CreateChangeSetTask) :: _ = generateTasks(data)

    create.amiParameterMap should be(Map("AMI" -> Map("myApp1" -> "fakeApp1"), "RouterAMI" -> Map("myApp2" -> "fakeApp2")))
  }

  it should "use all values on amiParametersToTags" in {
    val data: Map[String, JsValue] = Map(
      "amiParametersToTags" -> Json.obj(
        "AMI" -> Json.obj("myApp1" -> JsString("fakeApp1")),
        "myAMI" -> Json.obj("myApp2" -> JsString("fakeApp2"))
    ))

    val (create: CreateChangeSetTask) :: _ = generateTasks(data)

    create.amiParameterMap should be(Map(
      "AMI" -> Map("myApp1" -> "fakeApp1"),
      "myAMI" -> Map("myApp2" -> "fakeApp2")
    ))
  }

  it should "respect a non-default amiParameter" in {
    val data: Map[String, JsValue] = Map(
      "amiParameter" -> JsString("myAMI"),
      "amiTags" -> Json.obj("myApp" -> JsString("fakeApp"))
    )

    val (create: CreateChangeSetTask) :: _ = generateTasks(data)

    create.amiParameterMap should be(Map("myAMI" -> Map("myApp" -> "fakeApp")))
  }


  it should "respect the defaults for amiTags and amiParameter" in {
    val data: Map[String, JsValue] = Map("amiTags" -> Json.obj("myApp" -> JsString("fakeApp")))

    val (create: CreateChangeSetTask) :: _ = generateTasks(data)
    create.amiParameterMap should be(Map("AMI" -> Map("myApp" -> "fakeApp")))
  }

  it should "add an implicit Encrypted tag when amiEncrypted is true" in {
    val data: Map[String, JsValue] = Map("amiTags" -> Json.obj("myApp" -> JsString("fakeApp")), "amiEncrypted" -> JsBoolean(true))

    val (create: CreateChangeSetTask) :: _ = generateTasks(data)
    create.amiParameterMap should be(Map("AMI" -> Map("myApp" -> "fakeApp", "Encrypted" -> "true")))
  }

  it should "allow an explicit Encrypted tag when amiEncrypted is true" in {
    val data: Map[String, JsValue] = Map("amiTags" -> Json.obj("myApp" -> JsString("fakeApp"), "Encrypted" -> JsString("monkey")), "amiEncrypted" -> JsBoolean(true))

    val (create: CreateChangeSetTask) :: _ = generateTasks(data)
    create.amiParameterMap should be(Map("AMI" -> Map("myApp" -> "fakeApp", "Encrypted" -> "monkey")))
  }

  "UpdateCloudFormationTask" should "substitute stack and stage parameters" in {
    val templateParameters =
      Seq(TemplateParameter("param1", false), TemplateParameter("Stack", false), TemplateParameter("Stage", false))
    val combined = UpdateCloudFormationTask.combineParameters(Stack("cfn"), PROD, templateParameters, Map("param1" -> "value1"))

    combined should be(Map(
      "param1" -> SpecifiedValue("value1"),
      "Stack" -> SpecifiedValue("cfn"),
      "Stage" -> SpecifiedValue("PROD")
      ))
  }

  it should "default required parameters to use existing parameters" in {
    val templateParameters =
      Seq(TemplateParameter("param1", true), TemplateParameter("param3", false), TemplateParameter("Stage", false))
    val combined = UpdateCloudFormationTask.combineParameters(Stack("cfn"), PROD, templateParameters, Map("param1" -> "value1"))

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
    LookupByName(Stack("cfn"), Stage("STAGE"), "stackname", prependStack = true, appendStage = true) shouldBe
      LookupByName("cfn-stackname-STAGE")
    LookupByName(Stack("cfn"), Stage("STAGE"), "stackname", prependStack = false, appendStage = true) shouldBe
      LookupByName("stackname-STAGE")
    LookupByName(Stack("cfn"), Stage("STAGE"), "stackname", prependStack = false, appendStage = false) shouldBe
      LookupByName("stackname")
  }

  it should "correctly create a LookupByTags from deploy parameters" in {
    val data: Map[String, JsValue] = Map()
    val app = App("app")
    val stack = Stack("cfn")
    val cfnStackName = s"cfn-app-PROD"
    val pkg = DeploymentPackage("app", app, data, "cloud-formation", S3Path("artifact-bucket", "test/123"),
      deploymentTypes)
    val target = DeployTarget(parameters(), stack, region)
    LookupByTags(pkg, target, reporter) shouldBe LookupByTags(Map("Stack" -> "cfn", "Stage" -> "PROD", "App" -> "app"))
  }

  "CloudFormationDeploymentTypeParameters unencryptedTagFilter" should "include when there is no encrypted tag" in {
    CloudFormationDeploymentTypeParameters.unencryptedTagFilter(Map("Bob" -> "bobbins")) shouldBe true
  }

  it should "include when there is an encrypted tag that is set to false" in {
    CloudFormationDeploymentTypeParameters.unencryptedTagFilter(Map("Bob" -> "bobbins", "Encrypted" -> "false")) shouldBe true
  }

  it should "exclude when there is an encrypted tag that is not set to false" in {
    CloudFormationDeploymentTypeParameters.unencryptedTagFilter(Map("Bob" -> "bobbins", "Encrypted" -> "something")) shouldBe false
    CloudFormationDeploymentTypeParameters.unencryptedTagFilter(Map("Bob" -> "bobbins", "Encrypted" -> "true")) shouldBe false
  }

  "CreateChangeSetTask" should "fail on create if createStackIfAbsent is false" in {
    val data: Map[String, JsValue] = Map("createStackIfAbsent" -> JsBoolean(false))
    val create = generateTasks(data).head.asInstanceOf[CreateChangeSetTask]

    intercept[FailException] {
      create.getChangeSetType(None, reporter)
    }
  }

  it should "perform create if existing stack is empty" in {
    val (create: CreateChangeSetTask) :: _ = generateTasks()
    create.getChangeSetType(None, reporter) should be(ChangeSetType.CREATE)
  }

  it should "perform update if existing stack is non-empty" in {
    val (create: CreateChangeSetTask) :: _ = generateTasks()
    create.getChangeSetType(Some(new CloudFormationStack()), reporter) should be(ChangeSetType.UPDATE)
  }

  "CheckChangeSetCreatedTask" should "pass on CREATE_COMPLETE" in {
    val _ :: (check: CheckChangeSetCreatedTask) :: _ = generateTasks()
    check.shouldStopWaiting("CREATE_COMPLETE", "", List.empty, reporter) should be(true)
  }

  it should "pass on FAILED if there are no changes to execute" in {
    val _ :: (check: CheckChangeSetCreatedTask) :: _ = generateTasks()
    check.shouldStopWaiting("FAILED", "", List.empty, reporter) should be(true)
  }

  it should "fail on FAILED" in {
    val _ :: (check: CheckChangeSetCreatedTask) :: _ = generateTasks()

    intercept[FailException] {
      check.shouldStopWaiting("FAILED", "", List(new Change()), reporter)
    }
  }

  it should "continue on any other status" in {
    val _ :: (check: CheckChangeSetCreatedTask) :: _ = generateTasks()
    check.shouldStopWaiting("UPDATE_IN_PROGRESS", "", List.empty, reporter) should be(false)
  }
}
