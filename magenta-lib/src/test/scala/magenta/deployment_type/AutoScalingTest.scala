package magenta

import java.util.UUID

import com.amazonaws.regions.RegionUtils
import com.amazonaws.services.s3.AmazonS3
import magenta.artifact.S3Path
import magenta.deployment_type.AutoScaling
import magenta.fixtures._
import magenta.tasks._
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.{JsNumber, JsString, JsValue}

class AutoScalingTest extends FlatSpec with Matchers {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  implicit val artifactClient: AmazonS3 = null
  val region = Region("eu-west-1")

  "auto-scaling with ELB package type" should "have a deploy action" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("asg-bucket")
    )

    val app = Seq(App("app"))

    val p = DeploymentPackage("app", app, data, "asg-elb", S3Path("artifact-bucket", "test/123/app"))

    AutoScaling.actions("deploy")(p)(DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(), UnnamedStack, region)) should be (List(
      CheckForStabilization(p, PROD, UnnamedStack, Region("eu-west-1")),
      CheckGroupSize(p, PROD, UnnamedStack, Region("eu-west-1")),
      SuspendAlarmNotifications(p, PROD, UnnamedStack, Region("eu-west-1")),
      TagCurrentInstancesWithTerminationTag(p, PROD, UnnamedStack, Region("eu-west-1")),
      DoubleSize(p, PROD, UnnamedStack, Region("eu-west-1")),
      HealthcheckGrace(20000),
      WaitForStabilization(p, PROD, UnnamedStack, 15 * 60 * 1000, Region("eu-west-1")),
      WarmupGrace(1000),
      WaitForStabilization(p, PROD, UnnamedStack, 15 * 60 * 1000, Region("eu-west-1")),
      CullInstancesWithTerminationTag(p, PROD, UnnamedStack, Region("eu-west-1")),
      ResumeAlarmNotifications(p, PROD, UnnamedStack, Region("eu-west-1"))
    ))
  }

  "seconds to wait" should "be overridable" in {
    val data: Map[String, JsValue] = Map(
      "bucket" -> JsString("asg-bucket"),
      "secondsToWait" -> JsNumber(3 * 60),
      "healthcheckGrace" -> JsNumber(30),
      "warmupGrace" -> JsNumber(20)
    )

    val app = Seq(App("app"))

    val p = DeploymentPackage("app", app, data, "asg-elb", S3Path("artifact-bucket", "test/123/app"))

    AutoScaling.actions("deploy")(p)(DeploymentResources(reporter, lookupEmpty, artifactClient), DeployTarget(parameters(), UnnamedStack, region)) should be (List(
      CheckForStabilization(p, PROD, UnnamedStack, Region("eu-west-1")),
      CheckGroupSize(p, PROD, UnnamedStack, Region("eu-west-1")),
      SuspendAlarmNotifications(p, PROD, UnnamedStack, Region("eu-west-1")),
      TagCurrentInstancesWithTerminationTag(p, PROD, UnnamedStack, Region("eu-west-1")),
      DoubleSize(p, PROD, UnnamedStack, Region("eu-west-1")),
      HealthcheckGrace(30000),
      WaitForStabilization(p, PROD, UnnamedStack, 3 * 60 * 1000, Region("eu-west-1")),
      WarmupGrace(20000),
      WaitForStabilization(p, PROD, UnnamedStack, 3 * 60 * 1000, Region("eu-west-1")),
      CullInstancesWithTerminationTag(p, PROD, UnnamedStack, Region("eu-west-1")),
      ResumeAlarmNotifications(p, PROD, UnnamedStack, Region("eu-west-1"))
    ))
  }
}
