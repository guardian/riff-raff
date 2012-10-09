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

    autoscaling.perAppActions("deploy")(Stage("PROD")) should be (List(
      S3Upload(Stage("PROD"), "asg-bucket", new File("/tmp/packages/webapp/")),
      DoubleSize("app", Stage("PROD"))
    ))
  }
}
