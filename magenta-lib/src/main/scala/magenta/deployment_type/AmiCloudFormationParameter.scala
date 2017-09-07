package magenta.deployment_type

import magenta.deployment_type.CloudFormationDeploymentTypeParameters._
import magenta.tasks.{CheckUpdateEventsTask, UpdateAmiCloudFormationParameterTask}

object AmiCloudFormationParameter extends DeploymentType with CloudFormationDeploymentTypeParameters {
  val name = "ami-cloudformation-parameter"
  def documentation =
    """Update an AMI parameter in a CloudFormation stack.
      |
      |Given AMI tags, this will resolve the latest matching AMI and update the AMI parameter
      |on the provided CloudFormation stack.
      |
      |You will need to add this as a dependency to your autoscaling deploy in your riff-raff.yaml to guard against race conditions.
      |
      |The set of AWS permissions needed to let RiffRaff do an AMI updates are:
      |
      |    {
      |      "Statement": [
      |        {
      |          "Action": [
      |             "cloudformation:DescribeStacks",
      |             "cloudformation:UpdateStack",
      |             "cloudformation:DescribeStackEvents",
      |             "ec2:DescribeSecurityGroups",
      |             "iam:PassRole",
      |             "autoscaling:CreateLaunchConfiguration",
      |             "autoscaling:UpdateAutoScalingGroup",
      |             "autoscaling:DescribeLaunchConfigurations",
      |             "autoscaling:DescribeScalingActivities",
      |             "autoscaling:DeleteLaunchConfiguration"
      |          ],
      |          "Effect": "Allow",
      |          "Resource": [
      |            "*"
      |          ]
      |        }
      |      ]
      |    }
      |
      |You'll need to add this to the Riff-Raff IAM account used for your project.
    """.stripMargin

  val update = Action("update",
    """
      |Given AMI tags, this will resolve the latest matching AMI and update the AMI parameter
      | on the provided CloudFormation stack.
    """.stripMargin
  ){ (pkg, resources, target) => {
      implicit val keyRing = resources.assembleKeyring(target, pkg)
      val reporter = resources.reporter

      val amiParameterMap: Map[CfnParam, TagCriteria] = getAmiParameterMap(pkg, target, reporter)
      val cloudFormationStackLookupStrategy = getCloudFormationStackLookupStrategy(pkg, target, reporter)

      List(
        UpdateAmiCloudFormationParameterTask(
          target.region,
          cloudFormationStackLookupStrategy,
          amiParameterMap,
          resources.lookup.getLatestAmi,
          target.parameters.stage,
          target.stack
        ),
        CheckUpdateEventsTask(
          target.region,
          cloudFormationStackLookupStrategy
        )
      )
    }
  }

  def defaultActions = List(update)
}