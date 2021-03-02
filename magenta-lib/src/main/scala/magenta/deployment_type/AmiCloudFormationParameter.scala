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
          getLatestAmi(pkg, target, reporter, resources.lookup),
          target.parameters.stage,
          target.stack
        ),
        new CheckUpdateEventsTask(
          target.region,
          cloudFormationStackLookupStrategy
        )
      )
    }
  }

  def defaultActions = List(update)
}