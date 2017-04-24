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

  val publicReadAcl = Param[Boolean](
    "publicReadAcl",
     "Whether the uploaded artifacts should be given the PublicRead Canned ACL. (Default is true!)"
  ).defaultFromContext((pkg, _) => Right(pkg.legacyConfig))

  val secondsToWait = Param(
    "secondsToWait",
    """Number of seconds to wait for the ElasticSearch cluster to become green
      | (also used as the wait time for the instance termination)"""
  ).default(15 * 60)

  val deploy = Action(
    "deploy",
    """
      |Carries out the update of instances in an autoscaling group. We carry out the following tasks:
      | - tag existing instances in the ASG with a termination tag
      | - double the size of the auto-scaling group (new instances will have the new application)
      | - wait for the ES cluster to go green
      | - terminate previously tagged instances one at a time, waiting for the cluster to go green after each
      |   termination
      |
      |The action checks whether the auto-scaling group maxsize is big enough before starting the process and also
      |suspends and resumes cloud watch alarms in order to prevent false alarms.
    """.stripMargin
  ) { (pkg, resources, target) =>
    implicit val keyRing = resources.assembleKeyring(target, pkg)
    val reporter = resources.reporter
    val parameters = target.parameters
    val stack = target.stack
    List(
      CheckGroupSize(pkg, parameters.stage, stack, target.region),
      WaitForElasticSearchClusterGreen(pkg,
                                       parameters.stage,
                                       stack,
                                       secondsToWait(pkg, target, reporter) * 1000,
                                       target.region),
      SuspendAlarmNotifications(pkg, parameters.stage, stack, target.region),
      TagCurrentInstancesWithTerminationTag(pkg, parameters.stage, stack, target.region),
      DoubleSize(pkg, parameters.stage, stack, target.region),
      WaitForElasticSearchClusterGreen(pkg,
                                       parameters.stage,
                                       stack,
                                       secondsToWait(pkg, target, reporter) * 1000,
                                       target.region),
      CullElasticSearchInstancesWithTerminationTag(pkg,
                                                   parameters.stage,
                                                   stack,
                                                   secondsToWait(pkg, target, reporter) * 1000,
                                                   target.region),
      ResumeAlarmNotifications(pkg, parameters.stage, stack, target.region)
    )
  }

  val uploadArtifacts = Action("uploadArtifacts",
                               """
      |Uploads the files in the deployment's directory to the specified bucket.
    """.stripMargin) { (pkg, resources, target) =>
    implicit val keyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient = resources.artifactClient
    val reporter = resources.reporter
    val prefix: String = S3Upload.prefixGenerator(target.stack, target.parameters.stage, pkg.name)
    List(
      S3Upload(
        target.region,
        bucket(pkg, target, reporter),
        Seq(pkg.s3Package -> prefix),
        publicReadAcl = publicReadAcl(pkg, target, reporter)
      )
    )
  }

  def defaultActions = List(uploadArtifacts, deploy)
}
