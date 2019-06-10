package magenta.deployment_type

import java.util.UUID

import magenta._
import magenta.artifact.S3Path
import magenta.fixtures._
import magenta.tasks.CloudFormation.{SpecifiedValue, TemplateBody, UseExistingValue}
import magenta.tasks.UpdateCloudFormationTask._
import magenta.tasks._
import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito._
import org.scalatest.{FlatSpec, Inside, Matchers}
import play.api.libs.json.{JsBoolean, JsString, JsValue, Json}
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.{Stack => CfnStack, TemplateParameter => CfnTemplateParameter, _}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{Bucket, ListBucketsResponse}
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse

class CloudFormationTest extends FlatSpec with Matchers with Inside with MockitoSugar {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  implicit val reporter: DeployReporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: S3Client = null
  val region = Region("eu-west-1")
  val deploymentTypes: Seq[CloudFormation.type] = Seq(CloudFormation)
  val app = App("app")
  val testStack = Stack("cfn")
  val testStage = Stage("PROD")
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

  it should "ignore amiTags when amiParametersToTags and amiTags are provided" in {
    val data: Map[String, JsValue] = Map(
      "amiTags" -> Json.obj("myApp" -> JsString("fakeApp")),
      "amiParametersToTags" -> Json.obj(
        "AMI" -> Json.obj("myApp1" -> JsString("fakeApp1")),
        "RouterAMI" -> Json.obj("myApp2" -> JsString("fakeApp2"))
    ))

    val (create: CreateChangeSetTask) :: _ = generateTasks(data)

    create.unresolvedParameters.amiParameterMap should be(Map("AMI" -> Map("myApp1" -> "fakeApp1"), "RouterAMI" -> Map("myApp2" -> "fakeApp2")))
  }

  it should "use all values on amiParametersToTags" in {
    val data: Map[String, JsValue] = Map(
      "amiParametersToTags" -> Json.obj(
        "AMI" -> Json.obj("myApp1" -> JsString("fakeApp1")),
        "myAMI" -> Json.obj("myApp2" -> JsString("fakeApp2"))
    ))

    val (create: CreateChangeSetTask) :: _ = generateTasks(data)

    create.unresolvedParameters.amiParameterMap should be(Map(
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

    create.unresolvedParameters.amiParameterMap should be(Map("myAMI" -> Map("myApp" -> "fakeApp")))
  }


  it should "respect the defaults for amiTags and amiParameter" in {
    val data: Map[String, JsValue] = Map("amiTags" -> Json.obj("myApp" -> JsString("fakeApp")))

    val (create: CreateChangeSetTask) :: _ = generateTasks(data)
    create.unresolvedParameters.amiParameterMap should be(Map("AMI" -> Map("myApp" -> "fakeApp")))
  }

  it should "add an implicit Encrypted tag when amiEncrypted is true" in {
    val data: Map[String, JsValue] = Map("amiTags" -> Json.obj("myApp" -> JsString("fakeApp")), "amiEncrypted" -> JsBoolean(true))

    val (create: CreateChangeSetTask) :: _ = generateTasks(data)
    create.unresolvedParameters.amiParameterMap should be(Map("AMI" -> Map("myApp" -> "fakeApp", "Encrypted" -> "true")))
  }

  it should "allow an explicit Encrypted tag when amiEncrypted is true" in {
    val data: Map[String, JsValue] = Map("amiTags" -> Json.obj("myApp" -> JsString("fakeApp"), "Encrypted" -> JsString("monkey")), "amiEncrypted" -> JsBoolean(true))

    val (create: CreateChangeSetTask) :: _ = generateTasks(data)
    create.unresolvedParameters.amiParameterMap should be(Map("AMI" -> Map("myApp" -> "fakeApp", "Encrypted" -> "monkey")))
  }

  it should "default autoDistBucketParameter to DistBucket" in {
    val (create: CreateChangeSetTask) :: _ = generateTasks(Map.empty)
    create.unresolvedParameters.autoDistBucketParam shouldBe "DistBucket"
  }

  it should "accept an autoDistBucketParameter parameter" in {
    val data: Map[String, JsValue] = Map("autoDistBucketParameter" -> JsString("Bob"))

    val (create: CreateChangeSetTask) :: _ = generateTasks(data)
    create.unresolvedParameters.autoDistBucketParam shouldBe "Bob"
  }

  import CloudFormationParameters.combineParameters

  "CloudFormationParameters" should "substitute stack and stage parameters" in {
    val templateParameters =
      Seq(TemplateParameter("param1", default = false), TemplateParameter("Stack", default = false), TemplateParameter("Stage", default = false))
    val combined = combineParameters(Stack("cfn"), PROD, templateParameters, Map("param1" -> "value1"))

    combined should be(Map(
      "param1" -> SpecifiedValue("value1"),
      "Stack" -> SpecifiedValue("cfn"),
      "Stage" -> SpecifiedValue("PROD")
      ))
  }

  it should "default required parameters to use existing parameters" in {
    val templateParameters =
      Seq(TemplateParameter("param1", default = true), TemplateParameter("param3", default = false), TemplateParameter("Stage", default = false))
    val combined = combineParameters(Stack("cfn"), PROD, templateParameters, Map("param1" -> "value1"))

    combined should be(Map(
      "param1" -> SpecifiedValue("value1"),
      "param3" -> UseExistingValue,
      "Stage" -> SpecifiedValue(PROD.name)
    ))
  }

  import CloudFormationParameters.convertParameters

  it should "convert specified parameter" in {
    convertParameters(Map("key" -> SpecifiedValue("value")), ChangeSetType.UPDATE, reporter) should
      contain only Parameter.builder().parameterKey("key").parameterValue("value").build()
  }

  it should "use existing value" in {
    convertParameters(Map("key" -> UseExistingValue), ChangeSetType.UPDATE, reporter) should
      contain only Parameter.builder().parameterKey("key").usePreviousValue(true).build()
  }

  it should "fail if using existing value on stack creation" in {
    intercept[FailException] {
      convertParameters(Map("key" -> UseExistingValue), ChangeSetType.CREATE, reporter)
    }
  }

  "CloudFormationStackLookupStrategy" should "correctly create a LookupByName from deploy parameters" in {
    LookupByName(Stack("cfn"), Stage("STAGE"), "stackname", prependStack = true, appendStage = true) shouldBe
      LookupByName("cfn-stackname-STAGE")
    LookupByName(Stack("cfn"), Stage("STAGE"), "stackname", prependStack = false, appendStage = true) shouldBe
      LookupByName("stackname-STAGE")
    LookupByName(Stack("cfn"), Stage("STAGE"), "stackname", prependStack = false, appendStage = false) shouldBe
      LookupByName("stackname")
  }

  it should "create new CFN stack names" in {
    import CloudFormationStackMetadata.getNewStackName

    getNewStackName(LookupByName("name-of-stack")) shouldBe "name-of-stack"
    getNewStackName(LookupByTags(Map("Stack" -> "stackName", "App" -> "appName", "Stage" -> "STAGE"))) shouldBe
      "stackName-STAGE-appName"
    getNewStackName(LookupByTags(Map("Stack" -> "stackName", "App" -> "appName", "Stage" -> "STAGE", "Extra" -> "extraBit"))) shouldBe
      "stackName-STAGE-appName-extraBit"
  }

  it should "correctly create a LookupByTags from deploy parameters" in {
    val data: Map[String, JsValue] = Map()
    val app = App("app")
    val stack = Stack("cfn")

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

  it should ""

  import CloudFormationStackMetadata.getChangeSetType

  "CreateChangeSetTask" should "fail on create if createStackIfAbsent is false" in {
    intercept[FailException] {
      getChangeSetType("test", stackExists = false, createStackIfAbsent = false, reporter)
    }
  }

  it should "perform create if existing stack is empty" in {
    getChangeSetType("test", stackExists = false, createStackIfAbsent = true, reporter) should be(ChangeSetType.CREATE)
  }

  it should "perform update if existing stack is non-empty" in {
    getChangeSetType("test", stackExists = true, createStackIfAbsent = true, reporter) should be(ChangeSetType.UPDATE)
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
      check.shouldStopWaiting("FAILED", "", List(Change.builder().build()), reporter)
    }
  }

  it should "continue on CREATE_IN_PROGRESS" in {
    val _ :: (check: CheckChangeSetCreatedTask) :: _ = generateTasks()
    check.shouldStopWaiting("CREATE_IN_PROGRESS", "", List.empty, reporter) should be(false)
  }

  "CloudFormationParameters" should "ignore autoDistBucket parameter if not in the template" in {
    val params = new CloudFormationParameters(testStack, testStage, region, None, Map.empty, Map.empty, "DistBucket", Map.empty)
    val s3Client = mock[S3Client]
    val stsClient = mock[StsClient]
    val cfnClient = mock[CloudFormationClient]
    when(cfnClient.validateTemplate(ValidateTemplateRequest.builder.templateBody("").build)).thenReturn(
      ValidateTemplateResponse.builder.parameters(
        CfnTemplateParameter.builder.parameterKey("AnotherKey").build
      ).build
    )
    val resolvedParams = params.resolve(TemplateBody(""), "123456789", ChangeSetType.UPDATE, reporter, cfnClient, s3Client, stsClient)
    resolvedParams shouldBe List(
      Parameter.builder.parameterKey("AnotherKey").usePreviousValue(true).build
    )
    verifyZeroInteractions(s3Client, stsClient)
  }

  "CloudFormationParameters" should "use autoDistBucket parameter if in template" in {
    val params = new CloudFormationParameters(testStack, testStage, region, None, Map.empty, Map.empty, "DistBucket", Map.empty)
    val s3Client = mock[S3Client]
    val stsClient = mock[StsClient]
    when(stsClient.getCallerIdentity()).thenReturn(GetCallerIdentityResponse.builder.account("123456789").build)
    when(s3Client.listBuckets()).thenReturn(
      ListBucketsResponse.builder.buckets(
        Bucket.builder.name("bucket-1").build,
        Bucket.builder.name(s"${AutoDistBucket.BUCKET_PREFIX}-123456789-${region.name}").build,
        Bucket.builder.name("bucket-3").build
      ).build
    )
    val cfnClient = mock[CloudFormationClient]
    when(cfnClient.validateTemplate(ValidateTemplateRequest.builder.templateBody("").build)).thenReturn(
      ValidateTemplateResponse.builder.parameters(
        CfnTemplateParameter.builder.parameterKey("DistBucket").build
      ).build
    )
    val resolvedParams = params.resolve(TemplateBody(""), "123456789", ChangeSetType.UPDATE, reporter, cfnClient, s3Client, stsClient)
    resolvedParams shouldBe List(
      Parameter.builder.parameterKey("DistBucket").parameterValue(s"${AutoDistBucket.BUCKET_PREFIX}-123456789-${region.name}").build
    )
  }
}
