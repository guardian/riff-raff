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
      |
      |The set of AWS permissions needed to let RiffRaff do an autoscaling deploy are:
      |
      |    {
      |      "Statement": [
      |        {
      |          "Action": [
      |            "autoscaling:DescribeAutoScalingGroups",
      |            "autoscaling:DescribeAutoScalingInstances",
      |            "autoscaling:DescribeTags",
      |            "autoscaling:SuspendProcesses",
      |            "autoscaling:ResumeProcesses",
      |            "autoscaling:SetDesiredCapacity",
      |            "autoscaling:TerminateInstanceInAutoScalingGroup",
      |            "ec2:CreateTags",
      |            "ec2:DescribeInstances",
      |            "elb:DescribeInstanceHealth",
      |            "elasticloadbalancing:DescribeInstanceHealth",
      |            "elasticloadbalancing:DeregisterInstancesFromLoadBalancer"
      |          ],
      |          "Effect": "Allow",
      |          "Resource": [
      |            "*"
      |          ]
      |        },
      |        {
      |          "Action": [
      |            "s3:*"
      |          ],
      |          "Effect": "Allow",
      |          "Resource": [
      |            "arn:aws:s3:::*"
      |          ]
      |        }
      |      ]
      |    }
      |
      |You'll need to add this to the Riff-Raff IAM account used for your project.
    """.stripMargin

  val bucket = Param[String]("bucket",
    """
      |S3 bucket name to upload artifact into.
      |
      |The path in the bucket is `<stack>/<stage>/<packageName>/<fileName>`.
    """.stripMargin
  )
  val secondsToWait = Param("secondsToWait", "Number of seconds to wait for instances to enter service").default(15 * 60)
  val healthcheckGrace = Param("healthcheckGrace", "Number of seconds to wait for the AWS api to stabilise").default(20)
  val warmupGrace = Param("warmupGrace", "Number of seconds to wait for the instances in the load balancer to warm up").default(1)

  val prefixStage = Param[Boolean]("prefixStage",
    documentation = "Whether to prefix `stage` to the S3 location"
  ).default(true)
  val prefixPackage = Param[Boolean]("prefixPackage",
    documentation = "Whether to prefix `package` to the S3 location"
  ).default(true)
  val prefixStack = Param[Boolean]("prefixStack",
    documentation = "Whether to prefix `stack` to the S3 location"
  ).default(true)

  val publicReadAcl = Param[Boolean]("publicReadAcl",
    "Whether the uploaded artifacts should be given the PublicRead Canned ACL"
  ).defaultFromPackage(_.legacyConfig)

  val defaultActions = List("uploadArtifacts", "deploy")

  def actions = {
    case "deploy" => (pkg) => (resources, target) => {
      implicit val keyRing = resources.assembleKeyring(target, pkg)
      val parameters = target.parameters
      val stack = target.stack
      List(
        CheckForStabilization(pkg, parameters.stage, stack, target.region),
        CheckGroupSize(pkg, parameters.stage, stack, target.region),
        SuspendAlarmNotifications(pkg, parameters.stage, stack, target.region),
        TagCurrentInstancesWithTerminationTag(pkg, parameters.stage, stack, target.region),
        DoubleSize(pkg, parameters.stage, stack, target.region),
        HealthcheckGrace(healthcheckGrace(pkg) * 1000),
        WaitForStabilization(pkg, parameters.stage, stack, secondsToWait(pkg) * 1000, target.region),
        WarmupGrace(warmupGrace(pkg) * 1000),
        WaitForStabilization(pkg, parameters.stage, stack, secondsToWait(pkg) * 1000, target.region),
        CullInstancesWithTerminationTag(pkg, parameters.stage, stack, target.region),
        ResumeAlarmNotifications(pkg, parameters.stage, stack, target.region)
      )
    }
    case "uploadArtifacts" => (pkg) => (resources, target) =>
      implicit val keyRing = resources.assembleKeyring(target, pkg)
      implicit val artifactClient = resources.artifactClient
      val prefix = S3Upload.prefixGenerator(
        stack = if (prefixStack(pkg)) Some(target.stack) else None,
        stage = if (prefixStage(pkg)) Some(target.parameters.stage) else None,
        packageName = if (prefixPackage(pkg)) Some(pkg.name) else None
      )
      if (pkg.legacyConfig && publicReadAcl.get(pkg).isEmpty)
        resources.reporter.warning(
          "DEPRECATED: publicReadAcl should be specified for an autoscaling deploy. Not setting this means that it " +
            "defaults to true which is insecure and probably not what you want. It is not a good idea for artifacts " +
            "to be publically available on the internet - it is much better to ensure this is set to false and your " +
            "instances download the artifact from S3 using IAM instance credentials. If you are CERTAIN that this is" +
            "what you want you can also get rid of this message by explicitly setting publicReadAcl to true."
        )
      List(
        S3Upload(
          target.region,
          bucket.get(pkg).orElse(target.stack.nameOption.map(stackName => s"$stackName-dist")).get,
          Seq(pkg.s3Package -> prefix),
          publicReadAcl = publicReadAcl(pkg)
        )
      )
  }
}
