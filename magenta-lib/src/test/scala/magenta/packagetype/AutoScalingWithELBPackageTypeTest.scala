package magenta

import java.io.File
import tasks._
import fixtures._
import net.liftweb.json.Implicits._
import net.liftweb.json.JsonAST.{JValue, JString, JArray}
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

class AutoScalingWithELBPackageTypeTest extends FlatSpec with ShouldMatchers {
  "auto-scaling with ELB package type" should "have a deploy action" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "asg-bucket"
    )

    val p = Package("app", Set.empty, data, "asg-elb", new File("/tmp/packages/webapp"))

    val autoscaling = new AutoScalingWithELB(p)

    autoscaling.perAppActions("deploy")(DeployInfo(Nil), parameters()) should be (List(
      DoubleSize("app", Stage("PROD")),
      WaitTillUpAndInELB("app", PROD, 5 * 60 * 1000),
      CullInstancesWithoutVersion("app", PROD, Build("project", "version"), 8080, "management/manifest")
    ))
  }

  "seconds to wait" should "be overridable" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "asg-bucket",
      "secondsToWait" -> 3 * 60
    )

    val p = Package("app", Set.empty, data, "asg-elb", new File("/tmp/packages/webapp"))

    val autoscaling = new AutoScalingWithELB(p)

    autoscaling.perAppActions("deploy")(DeployInfo(Nil), parameters()) should be (List(
      DoubleSize("app", PROD),
      WaitTillUpAndInELB("app", PROD, 3 * 60 * 1000),
      CullInstancesWithoutVersion("app", PROD, Build("project", "version"), 8080, "management/manifest")
    ))
  }
}
