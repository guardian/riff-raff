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
      | - scale up, wait for the new instances to become healthy and then scale back down
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
      |            "elasticloadbalancing:DescribeInstanceHealth",
      |            "elasticloadbalancing:DescribeTargetHealth",
      |            "elasticloadbalancing:DeregisterInstancesFromLoadBalancer",
      |            "elasticloadbalancing:DeregisterTargets"
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
      |
      |Despite there being a default for this we are migrating to always requiring it to be specified.
    """.stripMargin,
    optional = true,
    deprecatedDefault = true
  ).defaultFromContext((_, target) => Right(s"${target.stack.name}-dist"))
  val secondsToWait = Param("secondsToWait", "Number of seconds to wait for instances to enter service").default(15 * 60)
  val healthcheckGrace = Param("healthcheckGrace", "Number of seconds to wait for the AWS api to stabilise").default(20)
  val warmupGrace = Param("warmupGrace", "Number of seconds to wait for the instances in the load balancer to warm up").default(1)
  val terminationGrace = Param("terminationGrace", "Number of seconds to wait for the AWS api to stabilise after instance termination").default(10)

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
  ).default(false)

  val deploy = Action("deploy",
    """
      |Carries out the update of instances in an autoscaling group. We carry out the following tasks:
      | - tag existing instances in the ASG with a termination tag
      | - double the size of the auto-scaling group (new instances will have the new application)
      | - wait for the new instances to enter service
      | - terminate previously tagged instances
      |
      |The action checks whether the auto-scaling group maxsize is big enough before starting the process and also
      |suspends and resumes cloud watch alarms in order to prevent false alarms.
      |
      |There are some delays introduced in order to work around consistency issues in the AWS ASG APIs.
    """.stripMargin
  ) { (pkg, resources, target) =>
    implicit val keyRing = resources.assembleKeyring(target, pkg)
    val reporter = resources.reporter
    val parameters = target.parameters
    val stack = target.stack
    List(
      WaitForStabilization(pkg, parameters.stage, stack, 5 * 60 * 1000, target.region),
      CheckGroupSize(pkg, parameters.stage, stack, target.region),
      SuspendAlarmNotifications(pkg, parameters.stage, stack, target.region),
      TagCurrentInstancesWithTerminationTag(pkg, parameters.stage, stack, target.region),
      ProtectCurrentInstances(pkg, parameters.stage, stack, target.region),
      DoubleSize(pkg, parameters.stage, stack, target.region),
      HealthcheckGrace(pkg, parameters.stage, stack, target.region, healthcheckGrace(pkg, target, reporter) * 1000),
      WaitForStabilization(pkg, parameters.stage, stack, secondsToWait(pkg, target, reporter) * 1000, target.region),
      WarmupGrace(pkg, parameters.stage, stack, target.region, warmupGrace(pkg, target, reporter) * 1000),
      WaitForStabilization(pkg, parameters.stage, stack, secondsToWait(pkg, target, reporter) * 1000, target.region),
      CullInstancesWithTerminationTag(pkg, parameters.stage, stack, target.region),
      TerminationGrace(pkg, parameters.stage, stack, target.region, terminationGrace(pkg, target, reporter) * 1000),
      WaitForStabilization(pkg, parameters.stage, stack, secondsToWait(pkg, target, reporter) * 1000, target.region),
      ResumeAlarmNotifications(pkg, parameters.stage, stack, target.region)
    )
  }

  val uploadArtifacts = Action("uploadArtifacts",
    """
      |Uploads the files in the deployment's directory to the specified bucket.
    """.stripMargin
  ){ (pkg, resources, target) =>
    implicit val keyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient = resources.artifactClient
    implicit val stsClient = resources.stsClient
    val reporter = resources.reporter
    val prefix = S3Upload.prefixGenerator(
      stack = if (prefixStack(pkg, target, reporter)) Some(target.stack) else None,
      stage = if (prefixStage(pkg, target, reporter)) Some(target.parameters.stage) else None,
      packageName = if (prefixPackage(pkg, target, reporter)) Some(pkg.name) else None
    )
    List(
      S3Upload(
        target.region,
        bucket(pkg, target, reporter),
        Seq(pkg.s3Package -> prefix),
        publicReadAcl = publicReadAcl(pkg, target, reporter)
      )
    )
  }

  val defaultActions = List(uploadArtifacts, deploy)
}
