package magenta.packagetype

import magenta._
import java.io.File
import magenta.fixtures._
import net.liftweb.json.Implicits._
import net.liftweb.json.JsonAST.JValue
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import magenta.tasks._
import magenta.AutoScalingWithAnElasticIP
import magenta.Stage
import magenta.Package

class AutoScalingWithAnElasticIPPackageTypeTest extends FlatSpec with ShouldMatchers {

  "An AutoScalingWithAnElasticIP package type" should "associate an elastic IP with an instance" in {

    val elasticIP = "123.123.123.123"
    val data: Map[String, JValue] = Map(
      "bucket" -> "asg-bucket",
      "elasticIP" -> elasticIP
    )

    val pkgType = new AutoScalingWithAnElasticIP(
      Package("app", Set.empty, data, "asg-elb-eip", new File("/tmp/packages/webapp"))
    )

    pkgType.perAppActions("deploy")(DeployInfo(), parameters()) should be(List(
      CheckGroupSize("app", PROD),
      SuspendAlarmNotifications("app", PROD),
      TagCurrentInstancesWithTerminationTag("app", PROD),
      DoubleSize("app", Stage("PROD")),
      WaitForStabilization("app", PROD, 15 * 60 * 1000),
      HealthcheckGrace(0),
      WaitForStabilization("app", PROD, 15 * 60 * 1000),
      CullInstancesWithTerminationTag("app", PROD),
      WaitForStabilization("app", PROD, 15 * 60 * 1000),
      ResumeAlarmNotifications("app", PROD),
      SetElasticIPOfAnInstance("app", PROD, elasticIP)
    ))
  }

  it should "not associate an elastic IP if none has been set up" in {

    val data: Map[String, JValue] = Map(
      "bucket" -> "asg-bucket"
    )

    val pkgType = new AutoScalingWithAnElasticIP(
      Package("app", Set.empty, data, "asg-elb-eip", new File("/tmp/packages/webapp"))
    )

    pkgType.perAppActions("deploy")(DeployInfo(), parameters()) should be(List(
      CheckGroupSize("app", PROD),
      SuspendAlarmNotifications("app", PROD),
      TagCurrentInstancesWithTerminationTag("app", PROD),
      DoubleSize("app", Stage("PROD")),
      WaitForStabilization("app", PROD, 15 * 60 * 1000),
      HealthcheckGrace(0),
      WaitForStabilization("app", PROD, 15 * 60 * 1000),
      CullInstancesWithTerminationTag("app", PROD),
      WaitForStabilization("app", PROD, 15 * 60 * 1000),
      ResumeAlarmNotifications("app", PROD)
    ))
  }

}
