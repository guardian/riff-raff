package magenta.deployment_type

import magenta.deployment_type.CloudFormation.{CfnParam, TagCriteria}
import magenta.tasks.UpdateCloudFormationTask.{LookupByName, LookupByTags}
import magenta.tasks.{CheckUpdateEventsTask, UpdateAmiCloudFormationParameterTask}

object AmiCloudFormationParameter extends DeploymentType {
  val name = "ami-cloudformation-parameter"
  def documentation =
    """Update an AMI parameter in a CloudFormation stack.
      |
      |Given AMI tags, this will resolve the latest matching AMI and update the AMI parameter
      |on the provided CloudFormation stack.
    """.stripMargin

  val cloudformationStackByTags = Param[Boolean]("cloudFormationStackByTags",
    documentation =
      """When false we derive the stack using the `cloudFormationStackName`, `prependStackToCloudFormationStackName` and
        |`appendStageToCloudFormationStackName` parameters. When true we find the stack by looking for one with matching
        |stack, app and stage tags in the same way that autoscaling groups are discovered.""".stripMargin
  ).defaultFromContext((pkg, _) => Right(!pkg.legacyConfig))
  val cloudFormationStackName = Param[String]("cloudFormationStackName",
    documentation = "The name of the CloudFormation stack to update"
  ).defaultFromContext((pkg, _) => Right(pkg.name))
  val prependStackToCloudFormationStackName = Param[Boolean]("prependStackToCloudFormationStackName",
    documentation = "Whether to prepend '`stack`-' to the `cloudFormationStackName`, e.g. MyApp => service-preview-MyApp"
  ).default(true)
  val appendStageToCloudFormationStackName = Param[Boolean]("appendStageToCloudFormationStackName",
    documentation = "Whether to add '-`stage`' to the `cloudFormationStackName`, e.g. MyApp => MyApp-PROD"
  ).default(true)
  val amiTags = Param[Map[String,String]]("amiTags",
    documentation = "Specify the set of tags to use to find the latest AMI"
  ).default(Map.empty)

  val amiParameter = Param[String]("amiParameter",
    documentation = "The CloudFormation parameter name for the AMI"
  ).default("AMI")

  val amiParametersToTags = Param[Map[CfnParam, TagCriteria]]("amiParametersToTags",
    documentation =
      """AMI cloudformation parameter names mapped to the set of tags that should be used to look up an AMI.
      """.stripMargin
  ).default(Map.empty)

  val update = Action("update",
    """
      |Given AMI tags, this will resolve the latest matching AMI and update the AMI parameter
      | on the provided CloudFormation stack.
    """.stripMargin
  ){ (pkg, resources, target) => {
      implicit val keyRing = resources.assembleKeyring(target, pkg)
      val reporter = resources.reporter

      val amiParameterMap: Map[CfnParam, TagCriteria] = (amiParametersToTags.get(pkg), amiTags.get(pkg)) match {
        case (Some(parametersToTags), Some(tags)) =>
          reporter.warning("Both amiParametersToTags and amiTags supplied. Ignoring amiTags.")
          parametersToTags
        case (Some(parametersToTags), _) => parametersToTags
        case (None, Some(tags)) => Map(amiParameter(pkg, target, reporter) -> tags)
        case _ => Map.empty
      }

      val cloudFormationStackLookupStrategy = {
        if (cloudformationStackByTags(pkg, target, reporter)) {
          LookupByTags(pkg, target, reporter)
        } else {
          LookupByName(
            target.stack,
            target.parameters.stage,
            cloudFormationStackName(pkg, target, reporter),
            prependStack = prependStackToCloudFormationStackName(pkg, target, reporter),
            appendStage = appendStageToCloudFormationStackName(pkg, target, reporter)
          )
        }
      }

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