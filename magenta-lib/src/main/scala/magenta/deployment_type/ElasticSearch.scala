package magenta.deployment_type

import magenta.tasks._
import java.io.File

object ElasticSearch extends DeploymentType {
  def name = "elasticsearch"
  val documentation =
    """
      |A specialised version of the `autoscaling` deployment type that has a specialise health check process to
      |ensure that the resulting ElasticSearch cluster is green.
    """.stripMargin

  val bucket = Param[String]("bucket", "S3 bucket that the artifact should be uploaded into")

  val publicReadAcl =
    Param[Boolean]("publicReadAcl",
                   "Whether the uploaded artifacts should be given the PublicRead Canned ACL. (Default is true!)")
      .defaultFromPackage(_.legacyConfig)

  val secondsToWait = Param("secondsToWait", """Number of seconds to wait for the ElasticSearch cluster to become green
      | (also used as the wait time for the instance termination)""").default(15 * 60)

  def defaultActions = List("uploadArtifacts", "deploy")

  def actions = {
    case "deploy" =>
      (pkg) => (resources, target) =>
        {
          implicit val keyRing = resources.assembleKeyring(target, pkg)
          val parameters = target.parameters
          val stack = target.stack
          List(
            CheckGroupSize(pkg, parameters.stage, stack, target.region),
            WaitForElasticSearchClusterGreen(pkg, parameters.stage, stack, secondsToWait(pkg) * 1000, target.region),
            SuspendAlarmNotifications(pkg, parameters.stage, stack, target.region),
            TagCurrentInstancesWithTerminationTag(pkg, parameters.stage, stack, target.region),
            DoubleSize(pkg, parameters.stage, stack, target.region),
            WaitForElasticSearchClusterGreen(pkg, parameters.stage, stack, secondsToWait(pkg) * 1000, target.region),
            CullElasticSearchInstancesWithTerminationTag(pkg,
                                                         parameters.stage,
                                                         stack,
                                                         secondsToWait(pkg) * 1000,
                                                         target.region),
            ResumeAlarmNotifications(pkg, parameters.stage, stack, target.region)
          )
        }
    case "uploadArtifacts" =>
      (pkg) => (resources, target) =>
        implicit val keyRing = resources.assembleKeyring(target, pkg)
        implicit val artifactClient = resources.artifactClient
        val prefix: String = S3Upload.prefixGenerator(target.stack, target.parameters.stage, pkg.name)
        List(
          S3Upload(
            target.region,
            bucket(pkg),
            Seq(pkg.s3Package -> prefix),
            publicReadAcl = publicReadAcl(pkg)
          )
        )
  }
}
