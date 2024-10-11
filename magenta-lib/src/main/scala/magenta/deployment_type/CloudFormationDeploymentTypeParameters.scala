package magenta.deployment_type

import magenta.deployment_type.CloudFormationDeploymentTypeParameters.CfnParam
import magenta.tasks.ASG
import magenta.tasks.ASG.TagMatch
import magenta.tasks.UpdateCloudFormationTask.{
  CloudFormationStackLookupStrategy,
  LookupByName,
  LookupByTags
}
import magenta.{DeployReporter, DeployTarget, DeploymentPackage, Lookup}
import software.amazon.awssdk.services.autoscaling.AutoScalingClient

import java.time.Duration
import java.time.Duration.ofMinutes

object CloudFormationDeploymentTypeParameters {
  type TagCriteria = Map[String, String]
  type CfnParam = String

  /* If we are not looking for an encrypted AMI then we want to only allow images that either have no Encrypted tag
     or the Encrypted tag is explicitly set to false. */
  val unencryptedTagFilter: Map[String, String] => Boolean =
    _.get("Encrypted") match {
      case None          => true
      case Some("false") => true
      case Some(_)       => false
    }
}

trait CloudFormationDeploymentTypeParameters {
  this: DeploymentType =>
  import CloudFormationDeploymentTypeParameters._

  val cloudformationStackByTags = Param[Boolean](
    "cloudFormationStackByTags",
    documentation =
      """When false we derive the stack using the `cloudFormationStackName`, `prependStackToCloudFormationStackName` and
        |`appendStageToCloudFormationStackName` parameters. When true we find the stack by looking for one with matching
        |stack, app and stage tags in the same way that autoscaling groups are discovered.""".stripMargin
  ).default(true)
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
    optional = true,
    documentation = "Specify the set of tags to use to find the latest AMI"
  )

  val amiParameter = Param[CfnParam](
    "amiParameter",
    documentation = "The CloudFormation parameter name for the AMI"
  ).default("AMI")

  val amiParametersToTags = Param[Map[CfnParam, TagCriteria]](
    "amiParametersToTags",
    optional = true,
    documentation =
      """Use when you need to update more than one AMI in a single CloudFormation stack. This takes AMI cloudformation
        |parameter names mapped to the set of tags that should be used to look up an AMI.
        |
        |For example:
        |```
        |  amiParametersToTags:
        |    standardAMI:
        |      BuiltBy: amigo
        |      Recipe: my-AMI-recipe
        |    magicAMI:
        |      BuiltBy: amigo
        |      Recipe: my-more-magical-AMI-recipe
        |```
        |
        |This updates both the `standardAMI` and `magicAMI` parameters in an CFN stack with the latest AMI that matches
        |the provided tags.
      """.stripMargin
  )

  val amiEncrypted = Param[Boolean](
    "amiEncrypted",
    optional = true,
    documentation = """
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

  val minInstancesInServiceParameters = Param[Map[CfnParam, TagCriteria]](
    "minInstancesInServiceParameters",
    optional = true,
    documentation = """Mapping between a CloudFormation parameter controlling the MinInstancesInService property of an ASG UpdatePolicy and the ASG.
        |
        |For example:
        |```
        |  minInstancesInServiceParameters:
        |    MinInstancesInServiceForApi:
        |      App: my-api
        |    MinInstancesInServiceForFrontend:
        |      App: my-frontend
        |```
        |This instructs Riff-Raff that the CFN parameter `MinInstancesInServiceForApi` relates to an ASG tagged `App=my-api`.
        |Additional requirements of `Stack=<STACK BEING DEPLOYED>`, `Stage=<STAGE BEING DEPLOYED>` and `aws:cloudformation:stack-name=<CFN STACK BEING DEPLOYED>` are automatically added.
      """.stripMargin
  )

  val secondsToWaitForChangeSetCreation: Param[Duration] = Param
    .waitingSecondsFor(
      "secondsToWaitForChangeSetCreation",
      "the change set to be created"
    )
    .default(ofMinutes(15))

  def getCloudFormationStackLookupStrategy(
      pkg: DeploymentPackage,
      target: DeployTarget,
      reporter: DeployReporter
  ): CloudFormationStackLookupStrategy = {
    if (cloudformationStackByTags(pkg, target, reporter)) {
      LookupByTags(pkg, target, reporter)
    } else {
      LookupByName(
        target.stack,
        target.parameters.stage,
        cloudFormationStackName(pkg, target, reporter),
        prependStack =
          prependStackToCloudFormationStackName(pkg, target, reporter),
        appendStage =
          appendStageToCloudFormationStackName(pkg, target, reporter)
      )
    }
  }

  def prefixEncrypted(
      isEncrypted: Boolean
  )(originalTags: TagCriteria): TagCriteria = {
    if (isEncrypted) {
      Map("Encrypted" -> "true") ++ originalTags
    } else originalTags
  }

  def getAmiParameterMap(
      pkg: DeploymentPackage,
      target: DeployTarget,
      reporter: DeployReporter
  ): Map[CfnParam, TagCriteria] = {
    val map: Map[CfnParam, TagCriteria] =
      (amiParametersToTags.get(pkg), amiTags.get(pkg)) match {
        case (Some(parametersToTags), Some(tags)) =>
          reporter.warning(
            "Both amiParametersToTags and amiTags supplied. Ignoring amiTags."
          )
          parametersToTags
        case (Some(parametersToTags), _) => parametersToTags
        case (None, Some(tags)) =>
          Map(amiParameter(pkg, target, reporter) -> tags)
        case _ => Map.empty
      }
    map.view
      .mapValues(prefixEncrypted(amiEncrypted(pkg, target, reporter)))
      .toMap
  }

  def getLatestAmi(
      pkg: DeploymentPackage,
      target: DeployTarget,
      reporter: DeployReporter,
      lookup: Lookup
  ): CfnParam => String => String => Map[String, String] => Option[String] = {
    amiCfnParam =>
      { accountNumber =>
        {
          val useEncryptedAmi: Boolean = amiParametersToTags.get(pkg) match {
            case Some(params) =>
              params
                .getOrElse(amiCfnParam, Map.empty)
                .get("Encrypted")
                .flatMap(_.toBooleanOption)
                .getOrElse(
                  // When `amiParametersToTags/Encrypted` is not explicitly set, fallback to `amiEncrypted`
                  amiEncrypted(pkg, target, reporter)
                )
            case _ => amiEncrypted(pkg, target, reporter)
          }

          if (useEncryptedAmi) {
            lookup.getLatestAmi(Some(accountNumber), _ => true)
          } else {
            lookup.getLatestAmi(None, unencryptedTagFilter)
          }
        }
      }
  }

  def getMinInServiceTagRequirements(
      pkg: DeploymentPackage,
      target: DeployTarget
  ): Map[CfnParam, List[TagMatch]] = {
    minInstancesInServiceParameters.get(pkg) match {
      case Some(params) =>
        val stackStageTags = List(
          TagMatch("Stack", target.stack.name),
          TagMatch("Stage", target.parameters.stage.name)
        )
        params.map({ case (cfnParam, tagRequirements) =>
          cfnParam -> {
            tagRequirements
              .map({ case (key, value) => TagMatch(key, value) })
              .toList ++ stackStageTags
          }
        })
      case _ => Map.empty
    }
  }
}
