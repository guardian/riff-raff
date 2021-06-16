package magenta

import java.util.UUID
import magenta.artifact.S3Path
import magenta.deployment_type.{AutoScaling, AutoScalingGroupLookup}
import magenta.fixtures._
import magenta.tasks._
import org.mockito.ArgumentMatchersSugar
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsNumber, JsString, JsValue}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient

import scala.concurrent.ExecutionContext.global

class AutoScalingTest extends AnyFlatSpec with Matchers with MockitoSugar with ArgumentMatchersSugar {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  implicit val reporter: DeployReporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: S3Client = null
  val stsClient: StsClient = null
  val region = Region("eu-west-1")
  val deploymentTypes: Seq[AutoScaling.type] = Seq(AutoScaling)

  "auto-scaling with ELB package type" should "have a deploy action" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("asg-bucket")
    )

    val app = App("app")

    val p = DeploymentPackage("app", app, data, "autoscaling", S3Path("artifact-bucket", "test/123/app"),
      deploymentTypes)

    withObjectMocked[AutoScalingGroupLookup.type] {
      when(AutoScalingGroupLookup.getTargetAsgName(*, *, *, *, *)) thenAnswer "test"
      val actual = AutoScaling.actionsMap("deploy").taskGenerator(p, DeploymentResources(reporter, lookupEmpty, artifactClient, stsClient, global), DeployTarget(parameters(), stack, region))
      val expected = List(
        WaitForStabilization("test", 5 * 60 * 1000, Region("eu-west-1")),
        CheckGroupSize("test", Region("eu-west-1")),
        SuspendAlarmNotifications("test", Region("eu-west-1")),
        TagCurrentInstancesWithTerminationTag("test", Region("eu-west-1")),
        ProtectCurrentInstances("test", Region("eu-west-1")),
        DoubleSize("test", Region("eu-west-1")),
        HealthcheckGrace("test", Region("eu-west-1"), 20000),
        WaitForStabilization("test", 15 * 60 * 1000, Region("eu-west-1")),
        WarmupGrace("test", Region("eu-west-1"), 1000),
        WaitForStabilization("test", 15 * 60 * 1000, Region("eu-west-1")),
        CullInstancesWithTerminationTag("test", Region("eu-west-1")),
        TerminationGrace("test", Region("eu-west-1"), 10000),
        WaitForStabilization("test", 15 * 60 * 1000, Region("eu-west-1")),
        ResumeAlarmNotifications("test", Region("eu-west-1"))
      )
      actual shouldBe expected
    }
  }

  it should "default publicReadAcl to false when a new style package" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("asg-bucket")
    )

    val app = App("app")

    val p = DeploymentPackage("app", app, data, "autoscaling", S3Path("artifact-bucket", "test/123/app"), deploymentTypes)
    val resource = DeploymentResources(reporter, lookupEmpty, artifactClient, stsClient, global)
    AutoScaling.actionsMap("uploadArtifacts").taskGenerator(p, resource, DeployTarget(parameters(), stack, region)) should matchPattern {
      case List(S3Upload(_,_,_,_,_,_,false,_)) =>
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

    val p = DeploymentPackage("app", app, data, "autoscaling", S3Path("artifact-bucket", "test/123/app"),
      deploymentTypes)

    withObjectMocked[AutoScalingGroupLookup.type] {
      when(AutoScalingGroupLookup.getTargetAsgName(*, *, *, *, *)) thenAnswer "test"
      val actual = AutoScaling.actionsMap("deploy").taskGenerator(p, DeploymentResources(reporter, lookupEmpty, artifactClient, stsClient, global), DeployTarget(parameters(), stack, region))
      val expected = List(
        WaitForStabilization("test", 5 * 60 * 1000, Region("eu-west-1")),
        CheckGroupSize("test", Region("eu-west-1")),
        SuspendAlarmNotifications("test", Region("eu-west-1")),
        TagCurrentInstancesWithTerminationTag("test", Region("eu-west-1")),
        ProtectCurrentInstances("test", Region("eu-west-1")),
        DoubleSize("test", Region("eu-west-1")),
        HealthcheckGrace("test", Region("eu-west-1"), 30000),
        WaitForStabilization("test", 3 * 60 * 1000, Region("eu-west-1")),
        WarmupGrace("test", Region("eu-west-1"), 20000),
        WaitForStabilization("test", 3 * 60 * 1000, Region("eu-west-1")),
        CullInstancesWithTerminationTag("test", Region("eu-west-1")),
        TerminationGrace("test", Region("eu-west-1"), 11000),
        WaitForStabilization("test", 3 * 60 * 1000, Region("eu-west-1")),
        ResumeAlarmNotifications("test", Region("eu-west-1"))
      )
      actual shouldBe expected
    }
  }
}
