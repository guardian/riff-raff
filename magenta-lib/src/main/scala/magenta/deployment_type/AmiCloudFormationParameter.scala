package magenta.deployment_type

import magenta.deployment_type.CloudFormationDeploymentTypeParameters._
import magenta.tasks.UpdateCloudFormationTask.LookupByTags
import magenta.tasks.{
  CheckChangeSetCreatedTask,
  CheckUpdateEventsTask,
  CloudFormationParameters,
  CloudFormationStackMetadata,
  CreateAmiUpdateChangeSetTask,
  DeleteChangeSetTask,
  ExecuteChangeSetTask,
  UpdateAmiCloudFormationParameterTask
}
import org.joda.time.DateTime

object AmiCloudFormationParameter
    extends DeploymentType
    with CloudFormationDeploymentTypeParameters {
  val name = "ami-cloudformation-parameter"
  def documentation =
    """Update an AMI parameter in a CloudFormation stack.
      |
      |Given AMI tags, this will resolve the latest matching AMI and update the AMI parameter
      |on the provided CloudFormation stack.
      |
      |You will need to add this as a dependency to your autoscaling deploy in your riff-raff.yaml to guard against race conditions.
    """.stripMargin

  val update = Action(
    "update",
    "Given AMI tags, this will resolve the latest matching AMI and update the AMI parameter on the provided CloudFormation stack."
  ) { (pkg, resources, target) =>
    {
      implicit val keyRing = resources.assembleKeyring(target, pkg)
      val reporter = resources.reporter
      val amiParameterMap: Map[CfnParam, TagCriteria] =
        getAmiParameterMap(pkg, target, reporter)
      val cloudFormationStackLookupStrategy =
        getCloudFormationStackLookupStrategy(pkg, target, reporter)

      val changeSetName = s"${target.stack.name}-${new DateTime().getMillis}"

      val stackLookup = new CloudFormationStackMetadata(
        cloudFormationStackLookupStrategy,
        changeSetName,

        // We're updating an AMI. The stack should exist, so should never want to create it.
        createStackIfAbsent = false
      )

      val stackTags = cloudFormationStackLookupStrategy match {
        case LookupByTags(tags) => Some(tags)
        case _                  => None
      }

      val amiLookupFn = getLatestAmi(pkg, target, reporter, resources.lookup)

      val unresolvedParameters = new CloudFormationParameters(
        target = target,
        stackTags = stackTags,
        amiParameterMap = amiParameterMap,
        latestImage = amiLookupFn,

        // Not expecting any user parameters in this deployment type
        userParameters = Map.empty
      )

      List(
        new CreateAmiUpdateChangeSetTask(
          region = target.region,
          stackLookup = stackLookup,
          unresolvedParameters = unresolvedParameters
        ),
        new CheckChangeSetCreatedTask(
          target.region,
          stackLookup = stackLookup,
          duration = secondsToWaitForChangeSetCreation(pkg, target, reporter)
        ),
        new ExecuteChangeSetTask(
          target.region,
          stackLookup
        ),
        new DeleteChangeSetTask(
          target.region,
          stackLookup
        )
//        UpdateAmiCloudFormationParameterTask(
//          target.region,
//          cloudFormationStackLookupStrategy,
//          amiParameterMap,
//          getLatestAmi(pkg, target, reporter, resources.lookup),
//          target.parameters.stage,
//          target.stack
//        ),
//        new CheckUpdateEventsTask(
//          target.region,
//          cloudFormationStackLookupStrategy
//        )
      )
    }
  }

  def defaultActions = List(update)
}
