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
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsBoolean, JsNumber, JsString, JsValue}
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient

import scala.concurrent.ExecutionContext.global

class AutoScalingTest
    extends AnyFlatSpec
    with Matchers
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
        WaitForStabilization(testInfo, 5 * 60 * 1000, Region("eu-west-1")),
        CheckGroupSize(testInfo, Region("eu-west-1")),
        SuspendAlarmNotifications(testInfo, Region("eu-west-1")),
        TagCurrentInstancesWithTerminationTag(testInfo, Region("eu-west-1")),
        ProtectCurrentInstances(testInfo, Region("eu-west-1")),
        DoubleSize(testInfo, Region("eu-west-1")),
        HealthcheckGrace(testInfo, Region("eu-west-1"), 20000),
        WaitForStabilization(testInfo, 15 * 60 * 1000, Region("eu-west-1")),
        WarmupGrace(testInfo, Region("eu-west-1"), 1000),
        WaitForStabilization(testInfo, 15 * 60 * 1000, Region("eu-west-1")),
        CullInstancesWithTerminationTag(testInfo, Region("eu-west-1")),
        TerminationGrace(testInfo, Region("eu-west-1"), 10000),
        WaitForStabilization(testInfo, 15 * 60 * 1000, Region("eu-west-1")),
        ResumeAlarmNotifications(testInfo, Region("eu-west-1"))
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
        WaitForStabilization(testOldCfnAsg, 5 * 60 * 1000, Region("eu-west-1")),
        CheckGroupSize(testOldCfnAsg, Region("eu-west-1")),
        SuspendAlarmNotifications(testOldCfnAsg, Region("eu-west-1")),
        TagCurrentInstancesWithTerminationTag(
          testOldCfnAsg,
          Region("eu-west-1")
        ),
        ProtectCurrentInstances(testOldCfnAsg, Region("eu-west-1")),
        DoubleSize(testOldCfnAsg, Region("eu-west-1")),
        HealthcheckGrace(testOldCfnAsg, Region("eu-west-1"), 20000),
        WaitForStabilization(
          testOldCfnAsg,
          15 * 60 * 1000,
          Region("eu-west-1")
        ),
        WarmupGrace(testOldCfnAsg, Region("eu-west-1"), 1000),
        WaitForStabilization(
          testOldCfnAsg,
          15 * 60 * 1000,
          Region("eu-west-1")
        ),
        CullInstancesWithTerminationTag(testOldCfnAsg, Region("eu-west-1")),
        TerminationGrace(testOldCfnAsg, Region("eu-west-1"), 10000),
        WaitForStabilization(
          testOldCfnAsg,
          15 * 60 * 1000,
          Region("eu-west-1")
        ),
        ResumeAlarmNotifications(testOldCfnAsg, Region("eu-west-1")),
        // All tasks for testNewCdkAsg
        WaitForStabilization(testNewCdkAsg, 5 * 60 * 1000, Region("eu-west-1")),
        CheckGroupSize(testNewCdkAsg, Region("eu-west-1")),
        SuspendAlarmNotifications(testNewCdkAsg, Region("eu-west-1")),
        TagCurrentInstancesWithTerminationTag(
          testNewCdkAsg,
          Region("eu-west-1")
        ),
        ProtectCurrentInstances(testNewCdkAsg, Region("eu-west-1")),
        DoubleSize(testNewCdkAsg, Region("eu-west-1")),
        HealthcheckGrace(testNewCdkAsg, Region("eu-west-1"), 20000),
        WaitForStabilization(
          testNewCdkAsg,
          15 * 60 * 1000,
          Region("eu-west-1")
        ),
        WarmupGrace(testNewCdkAsg, Region("eu-west-1"), 1000),
        WaitForStabilization(
          testNewCdkAsg,
          15 * 60 * 1000,
          Region("eu-west-1")
        ),
        CullInstancesWithTerminationTag(testNewCdkAsg, Region("eu-west-1")),
        TerminationGrace(testNewCdkAsg, Region("eu-west-1"), 10000),
        WaitForStabilization(
          testNewCdkAsg,
          15 * 60 * 1000,
          Region("eu-west-1")
        ),
        ResumeAlarmNotifications(testNewCdkAsg, Region("eu-west-1"))
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
        WaitForStabilization(testInfo, 5 * 60 * 1000, Region("eu-west-1")),
        CheckGroupSize(testInfo, Region("eu-west-1")),
        SuspendAlarmNotifications(testInfo, Region("eu-west-1")),
        TagCurrentInstancesWithTerminationTag(testInfo, Region("eu-west-1")),
        ProtectCurrentInstances(testInfo, Region("eu-west-1")),
        DoubleSize(testInfo, Region("eu-west-1")),
        HealthcheckGrace(testInfo, Region("eu-west-1"), 30000),
        WaitForStabilization(testInfo, 3 * 60 * 1000, Region("eu-west-1")),
        WarmupGrace(testInfo, Region("eu-west-1"), 20000),
        WaitForStabilization(testInfo, 3 * 60 * 1000, Region("eu-west-1")),
        CullInstancesWithTerminationTag(testInfo, Region("eu-west-1")),
        TerminationGrace(testInfo, Region("eu-west-1"), 11000),
        WaitForStabilization(testInfo, 3 * 60 * 1000, Region("eu-west-1")),
        ResumeAlarmNotifications(testInfo, Region("eu-west-1"))
      )
      actual shouldBe expected
    }
  }
}
