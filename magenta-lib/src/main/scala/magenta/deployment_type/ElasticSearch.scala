package magenta.deployment_type

import magenta.tasks._
import java.io.File

object ElasticSearch extends DeploymentType with S3AclParams {
  def name = "elasticsearch"
  val documentation =
    """
      |A specialised version of the `autoscaling` deployment type that has a specialise health check process to
      |ensure that the resulting ElasticSearch cluster is green.
    """.stripMargin

  val bucket = Param[String]("bucket", "S3 bucket that the artifact should be uploaded into")
  val secondsToWait = Param("secondsToWait",
    """Number of seconds to wait for the ElasticSearch cluster to become green
      | (also used as the wait time for the instance termination)"""
  ).default(15 * 60)

  def perAppActions = {
    case "deploy" => (pkg) => (_, parameters) => {
      List(
        CheckGroupSize(pkg, parameters.stage),
        WaitForElasticSearchClusterGreen(pkg, parameters.stage, secondsToWait(pkg) * 1000),
        SuspendAlarmNotifications(pkg, parameters.stage),
        TagCurrentInstancesWithTerminationTag(pkg, parameters.stage),
        DoubleSize(pkg, parameters.stage),
        WaitForElasticSearchClusterGreen(pkg, parameters.stage, secondsToWait(pkg) * 1000),
        CullElasticSearchInstancesWithTerminationTag(pkg, parameters.stage, secondsToWait(pkg) * 1000),
        ResumeAlarmNotifications(pkg, parameters.stage)
      )
    }
    case "uploadArtifacts" => (pkg) => (_, parameters) =>
      List(
        S3Upload(parameters.stage, bucket(pkg), new File(pkg.srcDir.getPath + "/"), publicReadAcl = publicReadAcl(pkg))
      )
  }
}
