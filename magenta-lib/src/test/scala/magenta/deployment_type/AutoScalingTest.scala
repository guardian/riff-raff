package magenta

import java.io.File
import tasks._
import fixtures._
import net.liftweb.json.Implicits._
import net.liftweb.json.JsonAST.JValue
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import magenta.deployment_type.AutoScaling

class AutoScalingTest extends FlatSpec with ShouldMatchers {
  "auto-scaling with ELB package type" should "have a deploy action" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "asg-bucket"
    )

    val p = DeploymentPackage("app", Set.empty, data, "asg-elb", new File("/tmp/packages/webapp"))

    AutoScaling.perAppActions("deploy")(p)(lookupEmpty, parameters()) should be (List(
      CheckGroupSize("app", PROD),
      SuspendAlarmNotifications("app", PROD),
      TagCurrentInstancesWithTerminationTag("app", PROD),
      DoubleSize("app", Stage("PROD")),
      WaitForStabilization("app", PROD, 15 * 60 * 1000),
      HealthcheckGrace(0),
      WaitForStabilization("app", PROD, 15 * 60 * 1000),
      CullInstancesWithTerminationTag("app", PROD),
      ResumeAlarmNotifications("app", PROD)
    ))
  }

  "seconds to wait" should "be overridable" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "asg-bucket",
      "secondsToWait" -> 3 * 60,
      "healthcheckGrace" -> 30
    )

    val p = DeploymentPackage("app", Set.empty, data, "asg-elb", new File("/tmp/packages/webapp"))

    AutoScaling.perAppActions("deploy")(p)(lookupEmpty, parameters()) should be (List(
      CheckGroupSize("app", PROD),
      SuspendAlarmNotifications("app", PROD),
      TagCurrentInstancesWithTerminationTag("app", PROD),
      DoubleSize("app", PROD),
      WaitForStabilization("app", PROD, 3 * 60 * 1000),
      HealthcheckGrace(30000),
      WaitForStabilization("app", PROD, 3 * 60 * 1000),
      CullInstancesWithTerminationTag("app", PROD),
      ResumeAlarmNotifications("app", PROD)
    ))
  }
}
