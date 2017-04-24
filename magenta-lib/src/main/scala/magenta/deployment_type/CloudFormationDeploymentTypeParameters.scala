package magenta.deployment_type

import magenta.tasks.UpdateCloudFormationTask.{CloudFormationStackLookupStrategy, LookupByName, LookupByTags}
import magenta.{DeployReporter, DeployTarget, DeploymentPackage}

object CloudFormationDeploymentTypeParameters {
  type TagCriteria = Map[String, String]
  type CfnParam = String
}

trait CloudFormationDeploymentTypeParameters { this: DeploymentType =>
  import CloudFormationDeploymentTypeParameters._

  val cloudformationStackByTags = Param[Boolean](
    "cloudFormationStackByTags",
    documentation =
      """When false we derive the stack using the `cloudFormationStackName`, `prependStackToCloudFormationStackName` and
        |`appendStageToCloudFormationStackName` parameters. When true we find the stack by looking for one with matching
        |stack, app and stage tags in the same way that autoscaling groups are discovered.""".stripMargin
  ).defaultFromContext((pkg, _) => Right(!pkg.legacyConfig))

  val cloudFormationStackName = Param[String](
    "cloudFormationStackName",
    documentation = "The name of the CloudFormation stack to update"
  ).defaultFromContext((pkg, _) => Right(pkg.name))

  val prependStackToCloudFormationStackName = Param[Boolean](
    "prependStackToCloudFormationStackName",
    documentation =
      "Whether to prepend '`stack`-' to the `cloudFormationStackName`, e.g. MyApp => service-preview-MyApp"
  ).default(true)

  val appendStageToCloudFormationStackName = Param[Boolean](
    "appendStageToCloudFormationStackName",
    documentation =
      "Whether to add '-`stage`' to the `cloudFormationStackName`, e.g. MyApp => MyApp-PROD"
  ).default(true)

  val amiTags = Param[TagCriteria](
    "amiTags",
     optionalInYaml = true,
     documentation = "Specify the set of tags to use to find the latest AMI"
  )

  val amiParameter = Param[CfnParam](
    "amiParameter",
    documentation = "The CloudFormation parameter name for the AMI"
  ).default("AMI")

  val amiParametersToTags = Param[Map[CfnParam, TagCriteria]](
    "amiParametersToTags",
    optionalInYaml = true,
    documentation =
      """AMI cloudformation parameter names mapped to the set of tags that should be used to look up an AMI.
      """.stripMargin
  )

  def getCloudFormationStackLookupStrategy(pkg: DeploymentPackage,
                                           target: DeployTarget,
                                           reporter: DeployReporter): CloudFormationStackLookupStrategy = {
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

  def getAmiParameterMap(pkg: DeploymentPackage,
                         target: DeployTarget,
                         reporter: DeployReporter): Map[CfnParam, TagCriteria] = {
    (amiParametersToTags.get(pkg), amiTags.get(pkg)) match {
      case (Some(parametersToTags), Some(tags)) =>
        reporter.warning("Both amiParametersToTags and amiTags supplied. Ignoring amiTags.")
        parametersToTags
      case (Some(parametersToTags), _) => parametersToTags
      case (None, Some(tags)) => Map(amiParameter(pkg, target, reporter) -> tags)
      case _ => Map.empty
    }
  }
}
