package magenta.deployment_type

import magenta.tasks._
import java.io.File

object AutoScaling  extends DeploymentType {
  val name = "autoscaling"
  val documentation =
    """
      |Deploy to an autoscaling group in AWS.
      |
      |The approach of this deploy type is to:
      |
      | - upload a new application artifact to an S3 bucket (from which new instances download their application)
      | - tag existing instances in the ASG with a termination tag
      | - double the size of the auto-scaling group (new instances will have the new application)
      | - wait for the new instances to enter service
      | - terminate previously tagged instances
      |
      |The action checks whether the auto-scaling group maxsize is big enough before starting the process.
      |
      |It also suspends and resumes cloud watch alarms in order to prevent false alarms.
      |
      |This deploy type has two actions, `deploy` and `uploadArtifacts`. `uploadArtifacts` simply uploads the files
      |in the package directory to the specified bucket. `deploy` carries out the auto-scaling group rotation.
    """.stripMargin

  val bucket = Param[String]("bucket",
    """
      |S3 bucket name to upload artifact into.
      |
      |The path in the bucket is `<packageName>/<stage>/<fileName>`.
    """.stripMargin
  )
  val secondsToWait = Param("secondsToWait", "Number of seconds to wait for instances to enter service").default(15 * 60)
  val healthcheckGrace = Param("healthcheckGrace", "Number of seconds to wait for the AWS api to stabalise").default(0)

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
