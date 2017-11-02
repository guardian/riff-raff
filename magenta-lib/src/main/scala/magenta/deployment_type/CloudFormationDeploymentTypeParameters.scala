package magenta.deployment_type

import magenta.tasks.UpdateCloudFormationTask.{CloudFormationStackLookupStrategy, LookupByName, LookupByTags}
import magenta.{DeploymentPackage, DeployReporter, DeployTarget, Lookup}

object CloudFormationDeploymentTypeParameters {
  type TagCriteria = Map[String, String]
  type CfnParam = String

  /* If we are not looking for an encrypted AMI then we want to only allow images that either have no Encrypted tag
     or the Encrypted tag is explicitly set to false. */
  val unencryptedTagFilter: Map[String, String] => Boolean = _.get("Encrypted") match {
    case None => true
    case Some("false") => true
    case Some(_) => false
  }
}

trait CloudFormationDeploymentTypeParameters {
  this: DeploymentType =>
  import CloudFormationDeploymentTypeParameters._

  val cloudformationStackByTags = Param[Boolean]("cloudFormationStackByTags",
    documentation =
      """When false we derive the stack using the `cloudFormationStackName`, `prependStackToCloudFormationStackName` and
        |`appendStageToCloudFormationStackName` parameters. When true we find the stack by looking for one with matching
        |stack, app and stage tags in the same way that autoscaling groups are discovered.""".stripMargin
  ).default(true)
  val cloudFormationStackName = Param[String]("cloudFormationStackName",
    documentation = "The name of the CloudFormation stack to update"
  ).defaultFromContext((pkg, _) => Right(pkg.name))
  val prependStackToCloudFormationStackName = Param[Boolean]("prependStackToCloudFormationStackName",
    documentation = "Whether to prepend '`stack`-' to the `cloudFormationStackName`, e.g. MyApp => service-preview-MyApp"
  ).default(true)
  val appendStageToCloudFormationStackName = Param[Boolean]("appendStageToCloudFormationStackName",
    documentation = "Whether to add '-`stage`' to the `cloudFormationStackName`, e.g. MyApp => MyApp-PROD"
  ).default(true)

  val amiTags = Param[TagCriteria]("amiTags",
    optional = true,
    documentation = "Specify the set of tags to use to find the latest AMI"
  )

  val amiParameter = Param[CfnParam]("amiParameter",
    documentation = "The CloudFormation parameter name for the AMI"
  ).default("AMI")

  val amiParametersToTags = Param[Map[CfnParam, TagCriteria]]("amiParametersToTags",
    optional = true,
    documentation =
      """AMI cloudformation parameter names mapped to the set of tags that should be used to look up an AMI.
      """.stripMargin
  )

  val amiEncrypted = Param[Boolean]("amiEncrypted",
    optionalInYaml = true,
    documentation =
      """
        |Specify that you want to use an AMI with an encrypted root EBS volume.
        |
        |When this is set to `true`:
        |
        | - Riff-Raff only looks for AMIs that are in the same account as that being deployed to (AMIs with an encrypted
        |   root volume can only be used by instances in the same account as the AMI)
        | - Riff-Raff adds an extra tag `Encrypted=true` to the set of tags specified by `amiTags` or
        |   `amiParametersToTags` (this can be explicitly overridden if desired).
        |
        |When this is set to `false` (the default):
        |
        | - In order to preserve backwards compatibility the AMI search will always exclude AMIs that have any
        |   `Encrypted` tag that is not `false`.
      """.stripMargin
  ).default(false)

  def getCloudFormationStackLookupStrategy(pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter): CloudFormationStackLookupStrategy = {
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

  def prefixEncrypted(isEncrypted: Boolean)(originalTags: TagCriteria): TagCriteria = {
    if (isEncrypted) {
      Map("Encrypted" -> "true") ++ originalTags
    } else originalTags
  }

  def getAmiParameterMap(pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter): Map[CfnParam, TagCriteria] = {
    val map: Map[CfnParam, TagCriteria] = (amiParametersToTags.get(pkg), amiTags.get(pkg)) match {
      case (Some(parametersToTags), Some(tags)) =>
        reporter.warning("Both amiParametersToTags and amiTags supplied. Ignoring amiTags.")
        parametersToTags
      case (Some(parametersToTags), _) => parametersToTags
      case (None, Some(tags)) => Map(amiParameter(pkg, target, reporter) -> tags)
      case _ => Map.empty
    }
    map.mapValues(prefixEncrypted(amiEncrypted(pkg, target, reporter)))
  }

  def getLatestAmi(pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter,
                   lookup: Lookup): String => String => Map[String, String] => Option[String] =
  { accountNumber =>
    if (amiEncrypted(pkg, target, reporter)) {
      lookup.getLatestAmi(Some(accountNumber), _ => true)
    } else {
      lookup.getLatestAmi(None, unencryptedTagFilter)
    }
  }
}
