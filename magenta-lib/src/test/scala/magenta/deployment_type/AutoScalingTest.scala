package magenta

import java.util.UUID

import com.amazonaws.regions.RegionUtils
import com.amazonaws.services.s3.AmazonS3
import magenta.artifact.S3Path
import magenta.deployment_type.{AutoScaling, DeploymentType}
import magenta.fixtures._
import magenta.tasks._
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.{JsNumber, JsString, JsValue}

class AutoScalingTest extends FlatSpec with Matchers {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: AmazonS3 = null
  val region = Region("eu-west-1")
  val deploymentTypes = Seq(AutoScaling)

  "auto-scaling with ELB package type" should "have a deploy action" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("asg-bucket")
    )

    val app = Seq(App("app"))

    val p = DeploymentPackage("app", app, data, "autoscaling", S3Path("artifact-bucket", "test/123/app"), true,
      deploymentTypes)

    AutoScaling.actionsMap("deploy").taskGenerator(p, DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(), UnnamedStack, region)) should be (List(
      CheckForStabilization(p, PROD, UnnamedStack, Region("eu-west-1")),
      CheckGroupSize(p, PROD, UnnamedStack, Region("eu-west-1")),
      SuspendAlarmNotifications(p, PROD, UnnamedStack, Region("eu-west-1")),
      TagCurrentInstancesWithTerminationTag(p, PROD, UnnamedStack, Region("eu-west-1")),
      DoubleSize(p, PROD, UnnamedStack, Region("eu-west-1")),
      HealthcheckGrace(p, PROD, UnnamedStack, Region("eu-west-1"), 20000),
      WaitForStabilization(p, PROD, UnnamedStack, 15 * 60 * 1000, Region("eu-west-1")),
      WarmupGrace(p, PROD, UnnamedStack, Region("eu-west-1"), 1000),
      WaitForStabilization(p, PROD, UnnamedStack, 15 * 60 * 1000, Region("eu-west-1")),
      CullInstancesWithTerminationTag(p, PROD, UnnamedStack, Region("eu-west-1")),
      TerminationGrace(p, PROD, UnnamedStack, Region("eu-west-1"), 10000),
      WaitForStabilization(p, PROD, UnnamedStack, 15 * 60 * 1000, Region("eu-west-1")),
      ResumeAlarmNotifications(p, PROD, UnnamedStack, Region("eu-west-1"))
    ))
  }

  it should "default publicReadAcl to false when a new style package" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("asg-bucket")
    )

    val app = Seq(App("app"))

    val p = DeploymentPackage("app", app, data, "autoscaling", S3Path("artifact-bucket", "test/123/app"), false,
      deploymentTypes)

    AutoScaling.actionsMap("uploadArtifacts").taskGenerator(p, DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(), UnnamedStack, region)) should matchPattern {
      case List(S3Upload(_,_,_,_,_,false,_)) =>
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

    val app = Seq(App("app"))

    val p = DeploymentPackage("app", app, data, "autoscaling", S3Path("artifact-bucket", "test/123/app"), true,
      deploymentTypes)

    AutoScaling.actionsMap("deploy").taskGenerator(p, DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(), UnnamedStack, region)) should be (List(
      CheckForStabilization(p, PROD, UnnamedStack, Region("eu-west-1")),
      CheckGroupSize(p, PROD, UnnamedStack, Region("eu-west-1")),
      SuspendAlarmNotifications(p, PROD, UnnamedStack, Region("eu-west-1")),
      TagCurrentInstancesWithTerminationTag(p, PROD, UnnamedStack, Region("eu-west-1")),
      DoubleSize(p, PROD, UnnamedStack, Region("eu-west-1")),
      HealthcheckGrace(p, PROD, UnnamedStack, Region("eu-west-1"), 30000),
      WaitForStabilization(p, PROD, UnnamedStack, 3 * 60 * 1000, Region("eu-west-1")),
      WarmupGrace(p, PROD, UnnamedStack, Region("eu-west-1"), 20000),
      WaitForStabilization(p, PROD, UnnamedStack, 3 * 60 * 1000, Region("eu-west-1")),
      CullInstancesWithTerminationTag(p, PROD, UnnamedStack, Region("eu-west-1")),
      TerminationGrace(p, PROD, UnnamedStack, Region("eu-west-1"), 11000),
      WaitForStabilization(p, PROD, UnnamedStack, 3 * 60 * 1000, Region("eu-west-1")),
      ResumeAlarmNotifications(p, PROD, UnnamedStack, Region("eu-west-1"))
    ))
  }
}
