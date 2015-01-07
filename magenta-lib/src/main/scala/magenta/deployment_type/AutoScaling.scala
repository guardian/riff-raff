package magenta.deployment_type

import magenta.tasks._
import java.io.File

object AutoScaling  extends DeploymentType with S3AclParams {
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

  def perAppActions = {
    case "deploy" => (pkg) => (lookup, parameters, stack) => {
      implicit val keyRing = lookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      List(
        CheckForStabilization(pkg, parameters.stage, stack),
        CheckGroupSize(pkg, parameters.stage, stack),
        SuspendAlarmNotifications(pkg, parameters.stage, stack),
        TagCurrentInstancesWithTerminationTag(pkg, parameters.stage, stack),
        DoubleSize(pkg, parameters.stage, stack),
        HealthcheckGrace(healthcheckGrace(pkg) * 1000),
        WaitForStabilization(pkg, parameters.stage, stack, secondsToWait(pkg) * 1000),
        WarmupGrace(warmupGrace(pkg) * 1000),
        WaitForStabilization(pkg, parameters.stage, stack, secondsToWait(pkg) * 1000),
        CullInstancesWithTerminationTag(pkg, parameters.stage, stack),
        ResumeAlarmNotifications(pkg, parameters.stage, stack)
      )
    }
    case "uploadArtifacts" => (pkg) => (lookup, parameters, stack) =>
      implicit val keyRing = lookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      List(
        S3Upload(
          stack,
          parameters.stage,
          bucket.get(pkg).orElse(stack.nameOption.map(stackName => s"$stackName-dist")).get,
          new File(pkg.srcDir.getPath + "/"),
          publicReadAcl = publicReadAcl(pkg)
        )
      )
  }
}
