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
    case "deploy" => (pkg) => (logger, lookup, parameters, stack) => {
      implicit val keyRing = lookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      List(
        CheckGroupSize(pkg, parameters.stage, stack),
        WaitForElasticSearchClusterGreen(pkg, parameters.stage, stack, secondsToWait(pkg) * 1000),
        SuspendAlarmNotifications(pkg, parameters.stage, stack),
        TagCurrentInstancesWithTerminationTag(pkg, parameters.stage, stack),
        DoubleSize(pkg, parameters.stage, stack),
        WaitForElasticSearchClusterGreen(pkg, parameters.stage, stack, secondsToWait(pkg) * 1000),
        CullElasticSearchInstancesWithTerminationTag(pkg, parameters.stage, stack, secondsToWait(pkg) * 1000),
        ResumeAlarmNotifications(pkg, parameters.stage, stack)
      )
    }
    case "uploadArtifacts" => (pkg) => (logger, lookup, parameters, stack) =>
      implicit val keyRing = lookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      val prefix: String = S3Upload.prefixGenerator(stack, parameters.stage, pkg.name)
      List(
        S3Upload(
          bucket(pkg),
          Seq(new File(pkg.srcDir.getPath) -> prefix),
          publicReadAcl = publicReadAcl(pkg)
        )
      )
  }
}
