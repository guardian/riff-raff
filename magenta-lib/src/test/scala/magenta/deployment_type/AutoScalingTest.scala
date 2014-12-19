package magenta

import java.io.File
import tasks._
import fixtures._
import org.json4s._
import org.json4s.JsonDSL._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import magenta.deployment_type.AutoScaling

class AutoScalingTest extends FlatSpec with ShouldMatchers {
  implicit val fakeKeyRing = KeyRing(SystemUser(None))

  "auto-scaling with ELB package type" should "have a deploy action" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "asg-bucket"
    )

    val app = Seq(App("app"))

    val p = DeploymentPackage("app", app, data, "asg-elb", new File("/tmp/packages/webapp"))

    AutoScaling.perAppActions("deploy")(p)(lookupEmpty, parameters(), UnnamedStack) should be (List(
      CheckForStabilization(p, PROD, UnnamedStack),
      CheckGroupSize(p, PROD, UnnamedStack),
      SuspendAlarmNotifications(p, PROD, UnnamedStack),
      TagCurrentInstancesWithTerminationTag(p, PROD, UnnamedStack),
      DoubleSize(p, PROD, UnnamedStack),
      HealthcheckGrace(3000),
      WaitForStabilization(p, PROD, UnnamedStack, 15 * 60 * 1000),
      CullInstancesWithTerminationTag(p, PROD, UnnamedStack),
      ResumeAlarmNotifications(p, PROD, UnnamedStack)
    ))
  }

  "seconds to wait" should "be overridable" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "asg-bucket",
      "secondsToWait" -> 3 * 60,
      "healthcheckGrace" -> 30
    )

    val app = Seq(App("app"))

    val p = DeploymentPackage("app", app, data, "asg-elb", new File("/tmp/packages/webapp"))

    AutoScaling.perAppActions("deploy")(p)(lookupEmpty, parameters(), UnnamedStack) should be (List(
      CheckForStabilization(p, PROD, UnnamedStack),
      CheckGroupSize(p, PROD, UnnamedStack),
      SuspendAlarmNotifications(p, PROD, UnnamedStack),
      TagCurrentInstancesWithTerminationTag(p, PROD, UnnamedStack),
      DoubleSize(p, PROD, UnnamedStack),
      HealthcheckGrace(30000),
      WaitForStabilization(p, PROD, UnnamedStack, 3 * 60 * 1000),
      CullInstancesWithTerminationTag(p, PROD, UnnamedStack),
      ResumeAlarmNotifications(p, PROD, UnnamedStack)
    ))
  }
}
