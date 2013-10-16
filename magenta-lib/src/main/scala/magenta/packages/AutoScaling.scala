package magenta.packages

import magenta.tasks._
import java.io.File

object AutoScaling  extends PackageType {
  val name = "autoscaling"

  val params = Seq(bucket, secondsToWait, healthcheckGrace)

  val bucket = Param[String]("bucket")
  val secondsToWait = Param("secondsToWait", Some(15 * 60))
  val healthcheckGrace = Param("healthcheckGrace", Some(0))

  def perAppActions = {
    case "deploy" => (pkg) => (_, parameters) => {
      List(
        CheckGroupSize(pkg.name, parameters.stage),
        SuspendAlarmNotifications(pkg.name, parameters.stage),
        TagCurrentInstancesWithTerminationTag(pkg.name, parameters.stage),
        DoubleSize(pkg.name, parameters.stage),
        WaitForStabilization(pkg.name, parameters.stage, secondsToWait(pkg) * 1000),
        HealthcheckGrace(healthcheckGrace(pkg) * 1000),
        WaitForStabilization(pkg.name, parameters.stage, secondsToWait(pkg) * 1000),
        CullInstancesWithTerminationTag(pkg.name, parameters.stage),
        ResumeAlarmNotifications(pkg.name, parameters.stage)
      )
    }
    case "uploadArtifacts" => (pkg) => (_, parameters) =>
      List(
        S3Upload(parameters.stage, bucket(pkg), new File(pkg.srcDir.getPath + "/"))
      )
  }
}
