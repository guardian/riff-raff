package magenta.deployment_type

import magenta.Strategy.{Dangerous, MostlyHarmless}
import magenta._
import magenta.artifact.S3Path
import magenta.deployment_type.CloudFormationDeploymentTypeParameters.CfnParam
import magenta.deployment_type.{CloudFormation => CloudFormationDeploymentType}
import magenta.fixtures._
import magenta.tasks.CloudFormation.{SpecifiedValue, UseExistingValue}
import magenta.tasks.CloudFormationParameters.{
  ExistingParameter,
  InputParameter,
  TemplateParameter
}
import magenta.tasks.UpdateCloudFormationTask._
import magenta.tasks._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, Inside, OptionValues}
import play.api.libs.json.{JsBoolean, JsNumber, JsString, JsValue, Json}
import software.amazon.awssdk.services.cloudformation.model.{
  Change,
  ChangeSetType
}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient

import java.time.Duration.ofSeconds
import java.util.UUID
import scala.concurrent.ExecutionContext.global

object EmptyBuildTags extends BuildTags {
  def get(projectName: String, buildId: String): Map[String, String] = Map.empty
}
class CloudFormationTest
    extends AnyFlatSpec
    with Matchers
    with Inside
    with OptionValues
    with EitherValues {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  implicit val reporter: DeployReporter =
    DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: S3Client = null
  implicit val stsClient: StsClient = null

  val cloudformationDeploymentType = new CloudFormationDeploymentType(
    EmptyBuildTags
  )
  val region = Region("eu-west-1")
  val deploymentTypes: Seq[CloudFormationDeploymentType] = Seq(
    cloudformationDeploymentType
  )
  val app = App("app")
  val testStack = Stack("cfn")
  val cfnStackName = s"cfn-app-PROD"
  def p(data: Map[String, JsValue]) = DeploymentPackage(
    "app",
    app,
    data,
    "cloud-formation",
    S3Path("artifact-bucket", "test/123"),
    deploymentTypes
  )

  private def generateTasks(
      data: Map[String, JsValue] = Map(
        "cloudFormationStackByTags" -> JsBoolean(false)
      ),
      updateStrategy: Strategy = MostlyHarmless
  ) = {
    val resources = DeploymentResources(
      reporter,
      lookupEmpty,
      artifactClient,
      stsClient,
      global
    )
    cloudformationDeploymentType
      .actionsMap("updateStack")
      .taskGenerator(
        p(data),
        resources,
        DeployTarget(
          parameters(updateStrategy = updateStrategy),
          testStack,
          region
        )
      )
  }

  "parameters for durations" should "behave as documented and assume integers are in seconds" in {
    cloudformationDeploymentType.secondsToWaitForChangeSetCreation
      .parse(
        JsNumber(30)
      )
      .value shouldEqual ofSeconds(30)
  }

  it should "generate the tasks in the correct order when manageStackPolicy is false" in {
    val data: Map[String, JsValue] = Map(
      "manageStackPolicy" -> JsBoolean(false)
    )

    val tasks = generateTasks(data)
    tasks should have size (4)

    tasks(0) shouldBe a[CreateChangeSetTask]
    tasks(1) shouldBe a[CheckChangeSetCreatedTask]
    tasks(2) shouldBe a[ExecuteChangeSetTask]
    tasks(3) shouldBe a[DeleteChangeSetTask]
  }

  it should "generate stack policy tasks in the correct order when manageStackPolicy is true (default)" in {
    val tasks = generateTasks()
    tasks should have size (6)

    tasks(0) shouldBe a[SetStackPolicyTask]
    tasks(0)
      .asInstanceOf[SetStackPolicyTask]
      .stackPolicy shouldBe DenyReplaceDeletePolicy
    tasks(1) shouldBe a[CreateChangeSetTask]
    tasks(2) shouldBe a[CheckChangeSetCreatedTask]
    tasks(3) shouldBe a[ExecuteChangeSetTask]
    tasks(4) shouldBe a[DeleteChangeSetTask]
    tasks(5) shouldBe a[SetStackPolicyTask]
    tasks(5)
      .asInstanceOf[SetStackPolicyTask]
      .stackPolicy shouldBe AllowAllPolicy
  }

  it should "generate stack policy tasks in the correct order when manageStackPolicy is true and update strategy is Dangerous" in {
    val data: Map[String, JsValue] = Map(
      "manageStackPolicy" -> JsBoolean(true)
    )

    val tasks = generateTasks(data, updateStrategy = Dangerous)
    tasks should have size (6)

    tasks(0) shouldBe a[SetStackPolicyTask]
    tasks(0)
      .asInstanceOf[SetStackPolicyTask]
      .stackPolicy shouldBe AllowAllPolicy
    tasks(1) shouldBe a[CreateChangeSetTask]
    tasks(2) shouldBe a[CheckChangeSetCreatedTask]
    tasks(3) shouldBe a[ExecuteChangeSetTask]
    tasks(4) shouldBe a[DeleteChangeSetTask]
    tasks(5) shouldBe a[SetStackPolicyTask]
    tasks(5)
      .asInstanceOf[SetStackPolicyTask]
      .stackPolicy shouldBe AllowAllPolicy
  }

  it should "ignore amiTags when amiParametersToTags and amiTags are provided" in {
    val data: Map[String, JsValue] = Map(
      "amiTags" -> Json.obj("myApp" -> JsString("fakeApp")),
      "amiParametersToTags" -> Json.obj(
        "AMI" -> Json.obj("myApp1" -> JsString("fakeApp1")),
        "RouterAMI" -> Json.obj("myApp2" -> JsString("fakeApp2"))
      )
    )

    val _ :: (create: CreateChangeSetTask) :: _ = generateTasks(data)

    create.unresolvedParameters.amiParameterMap should be(
      Map(
        "AMI" -> Map("myApp1" -> "fakeApp1"),
        "RouterAMI" -> Map("myApp2" -> "fakeApp2")
      )
    )
  }

  it should "use all values on amiParametersToTags" in {
    val data: Map[String, JsValue] = Map(
      "amiParametersToTags" -> Json.obj(
        "AMI" -> Json.obj("myApp1" -> JsString("fakeApp1")),
        "myAMI" -> Json.obj("myApp2" -> JsString("fakeApp2"))
      )
    )

    val _ :: (create: CreateChangeSetTask) :: _ = generateTasks(data)

    create.unresolvedParameters.amiParameterMap should be(
      Map(
        "AMI" -> Map("myApp1" -> "fakeApp1"),
        "myAMI" -> Map("myApp2" -> "fakeApp2")
      )
    )
  }

  it should "respect a non-default amiParameter" in {
    val data: Map[String, JsValue] = Map(
      "amiParameter" -> JsString("myAMI"),
      "amiTags" -> Json.obj("myApp" -> JsString("fakeApp"))
    )

    val _ :: (create: CreateChangeSetTask) :: _ = generateTasks(data)

    create.unresolvedParameters.amiParameterMap should be(
      Map("myAMI" -> Map("myApp" -> "fakeApp"))
    )
  }

  it should "respect the defaults for amiTags and amiParameter" in {
    val data: Map[String, JsValue] =
      Map("amiTags" -> Json.obj("myApp" -> JsString("fakeApp")))

    val _ :: (create: CreateChangeSetTask) :: _ = generateTasks(data)
    create.unresolvedParameters.amiParameterMap should be(
      Map("AMI" -> Map("myApp" -> "fakeApp"))
    )
  }

  it should "add an implicit Encrypted tag when amiEncrypted is true" in {
    val data: Map[String, JsValue] = Map(
      "amiTags" -> Json.obj("myApp" -> JsString("fakeApp")),
      "amiEncrypted" -> JsBoolean(true)
    )

    val _ :: (create: CreateChangeSetTask) :: _ = generateTasks(data)
    create.unresolvedParameters.amiParameterMap should be(
      Map("AMI" -> Map("myApp" -> "fakeApp", "Encrypted" -> "true"))
    )
  }

  it should "allow an explicit Encrypted tag when amiEncrypted is true" in {
    val data: Map[String, JsValue] = Map(
      "amiTags" -> Json
        .obj("myApp" -> JsString("fakeApp"), "Encrypted" -> JsString("monkey")),
      "amiEncrypted" -> JsBoolean(true)
    )

    val _ :: (create: CreateChangeSetTask) :: _ = generateTasks(data)
    create.unresolvedParameters.amiParameterMap should be(
      Map("AMI" -> Map("myApp" -> "fakeApp", "Encrypted" -> "monkey"))
    )
  }

  import CloudFormationParameters.combineParameters

  "CloudFormationParameters combineParameters" should "substitute stack, stage and build ID parameters" in {
    val deployParameters =
      Map("Stack" -> "cfn", "Stage" -> "PROD", "BuildId" -> "543")
    val templateParameters =
      List(
        TemplateParameter("param1", default = false),
        TemplateParameter("Stack", default = false),
        TemplateParameter("Stage", default = false),
        TemplateParameter("BuildId", default = false)
      )
    val combined = combineParameters(
      deployParameters,
      Nil,
      templateParameters,
      Map("param1" -> "value1")
    )

    combined.right.value should be(
      Map(
        "param1" -> SpecifiedValue("value1"),
        "Stack" -> SpecifiedValue("cfn"),
        "Stage" -> SpecifiedValue("PROD"),
        "BuildId" -> SpecifiedValue("543")
      )
    )
  }

  it should "fail when params with no default are found when creating a new stack" in {
    val deployParameters =
      Map("Stack" -> "cfn", "Stage" -> "PROD", "BuildId" -> "543")
    val templateParameters =
      List(
        TemplateParameter("param1", default = false),
        TemplateParameter("param2", default = false)
      )
    val combined =
      combineParameters(deployParameters, Nil, templateParameters, Map.empty)

    combined.left.value should startWith(
      "Missing parameters for param1, param2:"
    )
  }

  it should "use existing values instead of defaults when updating a stack with no new parameters" in {
    val deployParameters =
      Map("Stack" -> "cfn", "Stage" -> "PROD", "BuildId" -> "543")
    val existingParameter =
      List(
        ExistingParameter("param1", "monkey", None),
        ExistingParameter("param2", "monkey", None)
      )
    val templateParameters =
      List(
        TemplateParameter("param1", default = true),
        TemplateParameter("param2", default = true)
      )
    val combined = combineParameters(
      deployParameters,
      existingParameter,
      templateParameters,
      Map.empty
    )

    combined.right.value should be(
      Map(
        "param1" -> UseExistingValue,
        "param2" -> UseExistingValue
      )
    )
  }

  it should "allow default values when updating a stack with new parameters" in {
    val deployParameters =
      Map("Stack" -> "cfn", "Stage" -> "PROD", "BuildId" -> "543")
    val existingParameter =
      List(
        ExistingParameter("param1", "monkey", None),
        ExistingParameter("param2", "cheese", None)
      )
    val templateParameters =
      List(
        TemplateParameter("param1", default = true),
        TemplateParameter("param2", default = false),
        TemplateParameter("param3", default = true)
      )
    val combined = combineParameters(
      deployParameters,
      existingParameter,
      templateParameters,
      Map.empty
    )

    combined.right.value should be(
      Map(
        "param1" -> UseExistingValue,
        "param2" -> UseExistingValue
      )
    )
  }

  it should "support new parameters with no default when the user directly supplies values" in {
    val deployParameters =
      Map("Stack" -> "cfn", "Stage" -> "PROD", "BuildId" -> "543")
    val existingParameter =
      List(
        ExistingParameter("param1", "monkey", None),
        ExistingParameter("param2", "cheese", None)
      )
    val templateParameters =
      List(
        TemplateParameter("param1", default = true),
        TemplateParameter("param2", default = false),
        TemplateParameter("param3", default = false)
      )
    val combined = combineParameters(
      deployParameters,
      existingParameter,
      templateParameters,
      Map("param3" -> "user-provided")
    )

    combined.right.value should be(
      Map(
        "param1" -> UseExistingValue,
        "param2" -> UseExistingValue,
        "param3" -> SpecifiedValue("user-provided")
      )
    )
  }

  it should "always specify parameters when the user directly supplies values" in {
    val deployParameters =
      Map("Stack" -> "cfn", "Stage" -> "PROD", "BuildId" -> "543")
    val existingParameter =
      List(
        ExistingParameter("param1", "monkey", None),
        ExistingParameter("param2", "cheese", None),
        ExistingParameter("param3", "something-else", None)
      )
    val templateParameters =
      List(
        TemplateParameter("param1", default = true),
        TemplateParameter("param2", default = false),
        TemplateParameter("param3", default = false)
      )
    val combined = combineParameters(
      deployParameters,
      existingParameter,
      templateParameters,
      Map("param3" -> "user-provided")
    )

    combined.right.value should be(
      Map(
        "param1" -> UseExistingValue,
        "param2" -> UseExistingValue,
        "param3" -> SpecifiedValue("user-provided")
      )
    )
  }

  it should "always specify parameters when the template contains deploy parameters" in {
    val deployParameters =
      Map("Stack" -> "cfn", "Stage" -> "PROD", "BuildId" -> "543")
    val existingParameter =
      List(
        ExistingParameter("Stack", "cfn", None),
        ExistingParameter("Stage", "CODE", None),
        ExistingParameter("BuildId", "540", None)
      )
    val templateParameters =
      List(
        TemplateParameter("Stack", default = true),
        TemplateParameter("Stage", default = false),
        TemplateParameter("BuildId", default = false)
      )
    val combined = combineParameters(
      deployParameters,
      existingParameter,
      templateParameters,
      Map.empty
    )

    combined.right.value should be(
      Map(
        "Stack" -> SpecifiedValue("cfn"),
        "Stage" -> SpecifiedValue("PROD"),
        "BuildId" -> SpecifiedValue("543")
      )
    )
  }

  it should "fail when there are new parameters in the template that don't have default values" in {
    val deployParameters =
      Map("Stack" -> "cfn", "Stage" -> "PROD", "BuildId" -> "543")
    val existingParameter =
      List(
        ExistingParameter("param1", "monkey", None),
        ExistingParameter("param2", "monkey", None)
      )
    val templateParameters =
      List(
        TemplateParameter("param1", default = false),
        TemplateParameter("param2", default = false),
        TemplateParameter("param3", default = false)
      )
    val combined = combineParameters(
      deployParameters,
      existingParameter,
      templateParameters,
      Map.empty
    )

    combined.left.value should startWith("Missing parameters for param3:")
  }

  it should "fail when the user provides parameters that are not in the template" in {
    val deployParameters =
      Map("Stack" -> "cfn", "Stage" -> "PROD", "BuildId" -> "543")
    val existingParameter =
      List(
        ExistingParameter("param1", "monkey", None),
        ExistingParameter("param2", "monkey", None)
      )
    val templateParameters =
      List(
        TemplateParameter("param1", default = false),
        TemplateParameter("param2", default = false)
      )
    val userParameters = Map("missing-param" -> "useless-value")
    val combined = combineParameters(
      deployParameters,
      existingParameter,
      templateParameters,
      userParameters
    )

    combined.left.value should startWith(
      "User specified parameters that are not in template: missing-param"
    )
  }

  it should "default required parameters to use existing parameters" in {
    val templateParameters =
      List(
        TemplateParameter("param1", default = true),
        TemplateParameter("param3", default = false),
        TemplateParameter("Stage", default = false)
      )
    val existingParameters =
      List(
        ExistingParameter("param1", "monkey", None),
        ExistingParameter("param3", "monkey", None),
        ExistingParameter("Stage", "BOB", None)
      )
    val deployParameters =
      Map("Stack" -> "cfn", "Stage" -> "PROD", "BuildId" -> "543")
    val combined = combineParameters(
      deployParameters,
      existingParameters,
      templateParameters,
      Map("param1" -> "value1")
    )

    combined.right.value should be(
      Map(
        "param1" -> SpecifiedValue("value1"),
        "param3" -> UseExistingValue,
        "Stage" -> SpecifiedValue(PROD.name)
      )
    )
  }

  it should "allow parameters to be removed from the template" in {
    val deployParameters =
      Map("Stack" -> "cfn", "Stage" -> "PROD", "BuildId" -> "543")
    val existingParameter =
      List(
        ExistingParameter("param1", "monkey", None),
        ExistingParameter("param2", "monkey", None),
        ExistingParameter("param3", "old-param", None)
      )
    val templateParameters =
      List(
        TemplateParameter("param1", default = false),
        TemplateParameter("param2", default = false)
      )
    val combined = combineParameters(
      deployParameters,
      existingParameter,
      templateParameters,
      Map.empty
    )

    combined.right.value shouldBe Map(
      "param1" -> UseExistingValue,
      "param2" -> UseExistingValue
    )
  }

  import CloudFormationParameters.convertParameters

  "CloudFormationParameters convertParameters" should "convert specified parameter" in {
    convertParameters(Map("key" -> SpecifiedValue("value"))) should
      contain only InputParameter("key", "value")
  }

  it should "convert existing values" in {
    convertParameters(Map("key" -> UseExistingValue)) should
      contain only InputParameter.usePreviousValue("key")
  }

  import CloudFormationParameters.resolve

  "CloudFormationParameters resolve" should "use default params when creating a new stack" in {
    val cfp = new CloudFormationParameters(
      DeployTarget(
        DeployParameters(
          Deployer("TestMan"),
          Build("test", "1"),
          Stage("PROD"),
          updateStrategy = MostlyHarmless
        ),
        Stack("deploy"),
        Region("eu-west-1")
      ),
      None,
      Map.empty,
      Map.empty,
      (_: CfnParam) =>
        (_: String) => (_: String) => (_: Map[String, String]) => None,
      Map.empty
    )

    val params = resolve(
      reporter,
      cfp,
      "0123456789",
      List(
        TemplateParameter("param1", default = true),
        TemplateParameter("param3", default = true),
        TemplateParameter("Stage", default = true)
      ),
      Nil,
      Map.empty
    )

    params.right.value shouldBe List(InputParameter("Stage", "PROD"))
  }

  it should "use existing values instead of defaults when updating a stack with no new parameters" in {
    val cfp = new CloudFormationParameters(
      DeployTarget(
        DeployParameters(
          Deployer("TestMan"),
          Build("test", "1"),
          Stage("PROD"),
          updateStrategy = MostlyHarmless
        ),
        Stack("deploy"),
        Region("eu-west-1")
      ),
      None,
      Map.empty,
      Map.empty,
      (_: CfnParam) =>
        (_: String) => (_: String) => (_: Map[String, String]) => None,
      Map.empty
    )

    val params = resolve(
      reporter,
      cfp,
      "0123456789",
      List(
        TemplateParameter("param1", default = true),
        TemplateParameter("param3", default = false),
        TemplateParameter("Stage", default = true)
      ),
      List(
        ExistingParameter("param1", "value1", None),
        ExistingParameter("param3", "value3", None),
        ExistingParameter("Stage", "BOB", None)
      ),
      Map.empty
    )

    params.right.value should contain theSameElementsAs (List(
      InputParameter("Stage", "PROD"),
      InputParameter.usePreviousValue("param3"),
      InputParameter.usePreviousValue("param1")
    ))
  }

  it should "allow default values when updating a stack with new parameters" in {
    val cfp = new CloudFormationParameters(
      DeployTarget(
        DeployParameters(
          Deployer("TestMan"),
          Build("test", "1"),
          Stage("PROD"),
          updateStrategy = MostlyHarmless
        ),
        Stack("deploy"),
        Region("eu-west-1")
      ),
      None,
      Map.empty,
      Map.empty,
      (_: CfnParam) =>
        (_: String) => (_: String) => (_: Map[String, String]) => None,
      Map.empty
    )

    val params = resolve(
      reporter,
      cfp,
      "0123456789",
      List(
        TemplateParameter("param1", default = true),
        TemplateParameter("param3", default = false),
        TemplateParameter("Stage", default = true)
      ),
      List(
        ExistingParameter("param3", "value3", None),
        ExistingParameter("Stage", "PROD", None)
      ),
      Map.empty
    )

    params.right.value should contain theSameElementsAs (List(
      InputParameter("Stage", "PROD"),
      InputParameter.usePreviousValue("param3")
    ))
  }

  it should "support new parameters with no default when the user directly supplies values" in {
    val userParameters = Map("param1" -> "user-value")

    val cfp = new CloudFormationParameters(
      DeployTarget(
        DeployParameters(
          Deployer("TestMan"),
          Build("test", "1"),
          Stage("PROD"),
          updateStrategy = MostlyHarmless
        ),
        Stack("deploy"),
        Region("eu-west-1")
      ),
      None,
      userParameters,
      Map.empty,
      (_: CfnParam) =>
        (_: String) => (_: String) => (_: Map[String, String]) => None,
      Map.empty
    )

    val params = resolve(
      reporter,
      cfp,
      "0123456789",
      List(
        TemplateParameter("param1", default = false),
        TemplateParameter("param3", default = false),
        TemplateParameter("Stage", default = true)
      ),
      List(
        ExistingParameter("param3", "value3", None),
        ExistingParameter("Stage", "PROD", None)
      ),
      Map.empty
    )

    params.right.value should contain theSameElementsAs (List(
      InputParameter("Stage", "PROD"),
      InputParameter.usePreviousValue("param3"),
      InputParameter("param1", "user-value")
    ))
  }

  it should "fail when there are new parameters in the template that don't have default values" in {
    val cfp = new CloudFormationParameters(
      DeployTarget(
        DeployParameters(
          Deployer("TestMan"),
          Build("test", "1"),
          Stage("PROD"),
          updateStrategy = MostlyHarmless
        ),
        Stack("deploy"),
        Region("eu-west-1")
      ),
      None,
      Map.empty,
      Map.empty,
      (_: CfnParam) =>
        (_: String) => (_: String) => (_: Map[String, String]) => None,
      Map.empty
    )

    val params = resolve(
      reporter,
      cfp,
      "0123456789",
      List(
        TemplateParameter("param1", default = false),
        TemplateParameter("param3", default = false),
        TemplateParameter("Stage", default = true)
      ),
      List(
        ExistingParameter("param3", "value3", None),
        ExistingParameter("Stage", "PROD", None)
      ),
      Map.empty
    )

    params.left.value should startWith("Missing parameters for param1:")
  }

  "CloudFormationStackLookupStrategy" should "correctly create a LookupByName from deploy parameters" in {
    LookupByName(
      Stack("cfn"),
      Stage("STAGE"),
      "stackname",
      prependStack = true,
      appendStage = true
    ) shouldBe
      LookupByName("cfn-stackname-STAGE")
    LookupByName(
      Stack("cfn"),
      Stage("STAGE"),
      "stackname",
      prependStack = false,
      appendStage = true
    ) shouldBe
      LookupByName("stackname-STAGE")
    LookupByName(
      Stack("cfn"),
      Stage("STAGE"),
      "stackname",
      prependStack = false,
      appendStage = false
    ) shouldBe
      LookupByName("stackname")
  }

  it should "create new CFN stack names" in {
    import CloudFormationStackMetadata.getNewStackName

    getNewStackName(LookupByName("name-of-stack")) shouldBe "name-of-stack"
    getNewStackName(
      LookupByTags(
        Map("Stack" -> "stackName", "App" -> "appName", "Stage" -> "STAGE")
      )
    ) shouldBe
      "stackName-STAGE-appName"
    getNewStackName(
      LookupByTags(
        Map(
          "Stack" -> "stackName",
          "App" -> "appName",
          "Stage" -> "STAGE",
          "Extra" -> "extraBit"
        )
      )
    ) shouldBe
      "stackName-STAGE-appName-extraBit"
  }

  it should "correctly create a LookupByTags from deploy parameters" in {
    val data: Map[String, JsValue] = Map()
    val app = App("app")
    val stack = Stack("cfn")

    val pkg = DeploymentPackage(
      "app",
      app,
      data,
      "cloud-formation",
      S3Path("artifact-bucket", "test/123"),
      deploymentTypes
    )
    val target = DeployTarget(parameters(), stack, region)
    LookupByTags(pkg, target, reporter) shouldBe LookupByTags(
      Map("Stack" -> "cfn", "Stage" -> "PROD", "App" -> "app")
    )
  }

  "CloudFormationDeploymentTypeParameters unencryptedTagFilter" should "include when there is no encrypted tag" in {
    CloudFormationDeploymentTypeParameters.unencryptedTagFilter(
      Map("Bob" -> "bobbins")
    ) shouldBe true
  }

  it should "include when there is an encrypted tag that is set to false" in {
    CloudFormationDeploymentTypeParameters.unencryptedTagFilter(
      Map("Bob" -> "bobbins", "Encrypted" -> "false")
    ) shouldBe true
  }

  it should "exclude when there is an encrypted tag that is not set to false" in {
    CloudFormationDeploymentTypeParameters.unencryptedTagFilter(
      Map("Bob" -> "bobbins", "Encrypted" -> "something")
    ) shouldBe false
    CloudFormationDeploymentTypeParameters.unencryptedTagFilter(
      Map("Bob" -> "bobbins", "Encrypted" -> "true")
    ) shouldBe false
  }

  import CloudFormationStackMetadata.getChangeSetType

  "CreateChangeSetTask" should "fail on create if createStackIfAbsent is false" in {
    intercept[FailException] {
      getChangeSetType(
        "test",
        stackExists = false,
        createStackIfAbsent = false,
        reporter
      )
    }
  }

  it should "perform create if existing stack is empty" in {
    getChangeSetType(
      "test",
      stackExists = false,
      createStackIfAbsent = true,
      reporter
    ) should be(ChangeSetType.CREATE)
  }

  it should "perform update if existing stack is non-empty" in {
    getChangeSetType(
      "test",
      stackExists = true,
      createStackIfAbsent = true,
      reporter
    ) should be(ChangeSetType.UPDATE)
  }

  "CheckChangeSetCreatedTask" should "pass on CREATE_COMPLETE" in {
    val _ :: _ :: (check: CheckChangeSetCreatedTask) :: _ = generateTasks()
    check.shouldStopWaiting(
      ChangeSetType.UPDATE,
      "CREATE_COMPLETE",
      "",
      List.empty,
      reporter
    ) should be(true)
  }

  it should "pass on FAILED if there are no changes to execute" in {
    val _ :: _ :: (check: CheckChangeSetCreatedTask) :: _ = generateTasks()
    check.shouldStopWaiting(
      ChangeSetType.UPDATE,
      "FAILED",
      "No updates are to be performed.",
      List.empty,
      reporter
    ) should be(true)
    check.shouldStopWaiting(
      ChangeSetType.UPDATE,
      "FAILED",
      "The submitted information didn't contain changes. Submit different information to create a change set.",
      List.empty,
      reporter
    ) should be(true)
  }

  it should "fail on a template error" in {
    val _ :: _ :: (check: CheckChangeSetCreatedTask) :: _ = generateTasks()
    intercept[FailException] {
      check.shouldStopWaiting(
        ChangeSetType.UPDATE,
        "FAILED",
        "A different error about your template.",
        List.empty,
        reporter
      ) should be(true)
    }
  }

  it should "fail on FAILED" in {
    val _ :: _ :: (check: CheckChangeSetCreatedTask) :: _ = generateTasks()

    intercept[FailException] {
      check.shouldStopWaiting(
        ChangeSetType.UPDATE,
        "FAILED",
        "",
        List(Change.builder().build()),
        reporter
      )
    }
  }

  it should "continue on CREATE_IN_PROGRESS" in {
    val _ :: _ :: (check: CheckChangeSetCreatedTask) :: _ = generateTasks()
    check.shouldStopWaiting(
      ChangeSetType.UPDATE,
      "CREATE_IN_PROGRESS",
      "",
      List.empty,
      reporter
    ) should be(false)
  }
}
