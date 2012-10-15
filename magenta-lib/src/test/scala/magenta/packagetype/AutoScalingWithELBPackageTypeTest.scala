package magenta

import java.io.File
import tasks._
import net.liftweb.util.TimeHelpers._
import net.liftweb.json.Implicits._
import tasks.BlockFirewall
import tasks.CheckUrls
import tasks.CopyFile
import tasks.Restart
import tasks.UnblockFirewall
import net.liftweb.json.JsonAST.{JValue, JString, JArray}
import tasks.WaitForPort
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

class AutoScalingWithELBPackageTypeTest extends FlatSpec with ShouldMatchers {
  "auto-scaling with ELB package type" should "have a deploy action" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "asg-bucket"
    )

    val p = Package("app", Set.empty, data, "asg-elb", new File("/tmp/packages/webapp"))

    val autoscaling = new AutoScalingWithELB(p)

    autoscaling.perAppActions("deploy")(parameters()) should be (List(
      S3Upload(Stage("PROD"), "asg-bucket", new File("/tmp/packages/webapp/")),
      DoubleSize("app", Stage("PROD")),
      WaitTillUpAndInELB("app", PROD, 5 * 60 * 1000),
      CullInstancesWithoutVersion("app", PROD, Build("project", "version"))
    ))
  }

  "seconds to wait" should "be overridable" in {
    val data: Map[String, JValue] = Map(
      "bucket" -> "asg-bucket",
      "secondsToWait" -> 3 * 60
    )

    val p = Package("app", Set.empty, data, "asg-elb", new File("/tmp/packages/webapp"))

    val autoscaling = new AutoScalingWithELB(p)

    autoscaling.perAppActions("deploy")(parameters()) should be (List(
      S3Upload(Stage("PROD"), "asg-bucket", new File("/tmp/packages/webapp/")),
      DoubleSize("app", PROD),
      WaitTillUpAndInELB("app", PROD, 3 * 60 * 1000),
      CullInstancesWithoutVersion("app", PROD, Build("project", "version"))
    ))
  }

  val PROD = Stage("PROD")
  def parameters(stage: Stage = PROD, version: String = "version") =
    DeployParameters(Deployer("tester"), Build("project", version), stage)
}
