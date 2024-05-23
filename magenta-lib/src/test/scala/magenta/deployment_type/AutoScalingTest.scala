package magenta.deployment_type

import magenta.{
  App,
  DeployReporter,
  DeployTarget,
  DeploymentPackage,
  DeploymentResources,
  KeyRing,
  Region,
  Stack,
  Stage,
  fixtures
}

import java.util.UUID
import magenta.artifact.S3Path
import magenta.fixtures._
import magenta.tasks.ASG.{TagAbsent, TagExists, TagMatch}
import magenta.tasks._
import org.mockito.ArgumentMatchersSugar
import org.mockito.MockitoSugar
import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsBoolean, JsNumber, JsString, JsValue}
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient

import java.time.Duration.{ofMinutes, ofSeconds}
import scala.concurrent.ExecutionContext.global

class AutoScalingTest
    extends AnyFlatSpec
    with Matchers
    with Inspectors
    with OptionValues
    with MockitoSugar
    with ArgumentMatchersSugar {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  implicit val reporter: DeployReporter =
    DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: S3Client = null
  val stsClient: StsClient = null
  val region = Region("eu-west-1")
  val deploymentTypes: Seq[AutoScaling.type] = Seq(AutoScaling)
  def testAsgInfo(name: String = "test") = {
    val asg = AutoScalingGroup
      .builder()
      .desiredCapacity(3)
      .autoScalingGroupName(name)
      .maxSize(10)
      .build()
    AutoScalingGroupInfo(asg, List(TagMatch("App", name)))
  }

  "AutoScaling parameters for durations" should "behave as documented and assume integers are in seconds" in {
    import AutoScaling._
    forAll(
      Seq(secondsToWait, healthcheckGrace, warmupGrace, terminationGrace)
    ) { param =>
      param.parse(JsNumber(30)).value shouldEqual ofSeconds(30)
    }
  }

  "AutoScalingGroupLookup.getTagRequirements" should "return the right tags for a basic app" in {
    val tagReqs = AutoScalingGroupLookup.getTagRequirements(
      Stage("testStage"),
      Stack("testStack"),
      App("testApp"),
      NoMigration
    )
    tagReqs.toSet shouldBe Set(
      TagMatch("Stage", "testStage"),
      TagMatch("Stack", "testStack"),
      TagMatch("App", "testApp")
    )
  }

  it should "return a TagExists when migration is MustBePresent" in {
    val tagReqs = AutoScalingGroupLookup.getTagRequirements(
      Stage("testStage"),
      Stack("testStack"),
      App("testApp"),
      MustBePresent
    )
    tagReqs.toSet shouldBe Set(
      TagMatch("Stage", "testStage"),
      TagMatch("Stack", "testStack"),
      TagMatch("App", "testApp"),
      TagExists("gu:riffraff:new-asg")
    )
  }

  it should "return a TagExists when migration is MustNotBePresent" in {
    val tagReqs = AutoScalingGroupLookup.getTagRequirements(
      Stage("testStage"),
      Stack("testStack"),
      App("testApp"),
      MustNotBePresent
    )
    tagReqs.toSet shouldBe Set(
      TagMatch("Stage", "testStage"),
      TagMatch("Stack", "testStack"),
      TagMatch("App", "testApp"),
      TagAbsent("gu:riffraff:new-asg")
    )
  }

  "auto-scaling with ELB package type" should "have a deploy action" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("asg-bucket")
    )

    val app = App("app")

    val p = DeploymentPackage(
      "app",
      app,
      data,
      "autoscaling",
      S3Path("artifact-bucket", "test/123/app"),
      deploymentTypes
    )

    withObjectMocked[AutoScalingGroupLookup.type] {
      val testInfo = testAsgInfo()
      when(AutoScalingGroupLookup.getTargetAsg(*, *, *, *, *)) thenAnswer Some(
        testInfo
      )
      val actual = AutoScaling
        .actionsMap("deploy")
        .taskGenerator(
          p,
          DeploymentResources(
            reporter,
            lookupEmpty,
            artifactClient,
            stsClient,
            global
          ),
          DeployTarget(parameters(), stack, region)
        )
      val expected = List(
        WaitForStabilization(testInfo, ofMinutes(5), region),
        CheckGroupSize(testInfo, region),
        SuspendAlarmNotifications(testInfo, region),
        TagCurrentInstancesWithTerminationTag(testInfo, region),
        ProtectCurrentInstances(testInfo, region),
        DoubleSize(testInfo, region),
        HealthcheckGrace(testInfo, region, ofSeconds(20)),
        WaitForStabilization(testInfo, ofMinutes(15), region),
        WarmupGrace(testInfo, region, ofSeconds(1)),
        WaitForStabilization(testInfo, ofMinutes(15), region),
        CullInstancesWithTerminationTag(testInfo, region),
        TerminationGrace(testInfo, region, ofSeconds(10)),
        ResumeAlarmNotifications(testInfo, region),
        WaitForCullToComplete(testInfo, ofMinutes(15), region),
        WaitForStabilization(testInfo, ofMinutes(15), region)
      )
      actual shouldBe expected
    }
  }

  "auto-scaling with asgMigrationInProgress=true" should "have a deploy action which updates two ASGs" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("asg-bucket"),
      "asgMigrationInProgress" -> JsBoolean(true)
    )

    val app = App("app")

    val p = DeploymentPackage(
      "app",
      app,
      data,
      "autoscaling",
      S3Path("artifact-bucket", "test/123/app"),
      deploymentTypes
    )

    withObjectMocked[AutoScalingGroupLookup.type] {
      val testOldCfnAsg = testAsgInfo("testOldCfnAsg")
      val testNewCdkAsg = testAsgInfo("testNewCdkAsg")
      when(AutoScalingGroupLookup.getTargetAsg(*, *, *, *, *))
        .thenReturn(Some(testOldCfnAsg), Some(testNewCdkAsg))
      val actual = AutoScaling
        .actionsMap("deploy")
        .taskGenerator(
          p,
          DeploymentResources(
            reporter,
            lookupEmpty,
            artifactClient,
            stsClient,
            global
          ),
          DeployTarget(parameters(), stack, region)
        )
      val expected = List(
        // All tasks for testOldCfnAsg
        WaitForStabilization(testOldCfnAsg, ofMinutes(5), region),
        CheckGroupSize(testOldCfnAsg, region),
        SuspendAlarmNotifications(testOldCfnAsg, region),
        TagCurrentInstancesWithTerminationTag(testOldCfnAsg, region),
        ProtectCurrentInstances(testOldCfnAsg, region),
        DoubleSize(testOldCfnAsg, region),
        HealthcheckGrace(testOldCfnAsg, region, ofSeconds(20)),
        WaitForStabilization(testOldCfnAsg, ofMinutes(15), region),
        WarmupGrace(testOldCfnAsg, region, ofSeconds(1)),
        WaitForStabilization(testOldCfnAsg, ofMinutes(15), region),
        CullInstancesWithTerminationTag(testOldCfnAsg, region),
        TerminationGrace(testOldCfnAsg, region, ofSeconds(10)),
        ResumeAlarmNotifications(testOldCfnAsg, region),
        WaitForCullToComplete(testOldCfnAsg, ofMinutes(15), region),
        WaitForStabilization(testOldCfnAsg, ofMinutes(15), region),
        // All tasks for testNewCdkAsg
        WaitForStabilization(testNewCdkAsg, ofMinutes(5), region),
        CheckGroupSize(testNewCdkAsg, region),
        SuspendAlarmNotifications(testNewCdkAsg, region),
        TagCurrentInstancesWithTerminationTag(testNewCdkAsg, region),
        ProtectCurrentInstances(testNewCdkAsg, region),
        DoubleSize(testNewCdkAsg, region),
        HealthcheckGrace(testNewCdkAsg, region, ofSeconds(20)),
        WaitForStabilization(testNewCdkAsg, ofMinutes(15), region),
        WarmupGrace(testNewCdkAsg, region, ofSeconds(1)),
        WaitForStabilization(testNewCdkAsg, ofMinutes(15), region),
        CullInstancesWithTerminationTag(testNewCdkAsg, region),
        TerminationGrace(testNewCdkAsg, region, ofSeconds(10)),
        ResumeAlarmNotifications(testNewCdkAsg, region),
        WaitForCullToComplete(testNewCdkAsg, ofMinutes(15), region),
        WaitForStabilization(testNewCdkAsg, ofMinutes(15), region)
      )
      actual shouldBe expected
    }
  }

  it should "default publicReadAcl to false when a new style package" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("asg-bucket")
    )

    val app = App("app")

    val p = DeploymentPackage(
      "app",
      app,
      data,
      "autoscaling",
      S3Path("artifact-bucket", "test/123/app"),
      deploymentTypes
    )
    val resource = DeploymentResources(
      reporter,
      lookupEmpty,
      artifactClient,
      stsClient,
      global
    )
    AutoScaling
      .actionsMap("uploadArtifacts")
      .taskGenerator(
        p,
        resource,
        DeployTarget(parameters(), stack, region)
      ) should matchPattern {
      case List(S3Upload(_, _, _, _, _, _, false, false, _)) =>
    }
  }

  "seconds to wait" should "be overridable" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("asg-bucket"),
      "secondsToWait" -> JsNumber(3 * 60),
      "healthcheckGrace" -> JsNumber(30),
      "warmupGrace" -> JsNumber(20),
      "terminationGrace" -> JsNumber(11)
    )

    val app = App("app")

    val p = DeploymentPackage(
      "app",
      app,
      data,
      "autoscaling",
      S3Path("artifact-bucket", "test/123/app"),
      deploymentTypes
    )

    withObjectMocked[AutoScalingGroupLookup.type] {
      val testInfo = testAsgInfo()
      when(AutoScalingGroupLookup.getTargetAsg(*, *, *, *, *)) thenAnswer Some(
        testInfo
      )
      val actual = AutoScaling
        .actionsMap("deploy")
        .taskGenerator(
          p,
          DeploymentResources(
            reporter,
            lookupEmpty,
            artifactClient,
            stsClient,
            global
          ),
          DeployTarget(parameters(), stack, region)
        )
      val expected = List(
        WaitForStabilization(testInfo, ofMinutes(5), region),
        CheckGroupSize(testInfo, region),
        SuspendAlarmNotifications(testInfo, region),
        TagCurrentInstancesWithTerminationTag(testInfo, region),
        ProtectCurrentInstances(testInfo, region),
        DoubleSize(testInfo, region),
        HealthcheckGrace(testInfo, region, ofSeconds(30)),
        WaitForStabilization(testInfo, ofMinutes(3), region),
        WarmupGrace(testInfo, region, ofSeconds(20)),
        WaitForStabilization(testInfo, ofMinutes(3), region),
        CullInstancesWithTerminationTag(testInfo, region),
        TerminationGrace(testInfo, region, ofSeconds(11)),
        ResumeAlarmNotifications(testInfo, region),
        WaitForCullToComplete(testInfo, ofMinutes(3), region),
        WaitForStabilization(testInfo, ofMinutes(3), region)
      )
      actual shouldBe expected
    }
  }
}
