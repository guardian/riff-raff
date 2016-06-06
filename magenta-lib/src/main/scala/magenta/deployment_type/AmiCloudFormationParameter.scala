package magenta.deployment_type

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

  val cloudFormationStackName = Param[String]("cloudFormationStackName",
    documentation = "The name of the CloudFormation stack to update"
  ).defaultFromPackage(_.name)
  val prependStackToCloudFormationStackName = Param[Boolean]("prependStackToCloudFormationStackName",
    documentation = "Whether to prepend '`stack`-' to the `cloudFormationStackName`, e.g. MyApp => service-preview-MyApp"
  ).default(true)
  val appendStageToCloudFormationStackName = Param[Boolean]("appendStageToCloudFormationStackName",
    documentation = "Whether to add '-`stage`' to the `cloudFormationStackName`, e.g. MyApp => MyApp-PROD"
  ).default(true)
  val cloudFormationStackTags = Param[Map[String, String]]("cloudFormationStackTags",
    documentation =
      """
        |The tags used to find the CloudFormation stack to update. The Stack and Stage tags will be automatically added.
        |
        |If this parameter is set, it will override any other parameters related to the CloudFormation stack name.
      """.stripMargin
  ).default(Map.empty)
  val amiTags = Param[Map[String,String]]("amiTags",
    documentation = "Specify the set of tags to use to find the latest AMI"
  )
  val amiParameter = Param[String]("amiParameter",
    documentation = "The CloudFormation parameter name for the AMI"
  ).default("AMI")

  override def perAppActions = {
    case "update" => pkg => (reporter, lookup, parameters, stack) => {
      implicit val keyRing = lookup.keyRing(parameters.stage, pkg.apps.toSet, stack)

      // Based on the supplied params, decide whether to look up the CF stack by name or by tags
      val cloudFormationStackLookupStrategy = {
        val tagsMap = cloudFormationStackTags(pkg)
        if (tagsMap.nonEmpty) {
          val withStackAndStageTags = tagsMap ++
            stack.nameOption.map("Stack" -> _) ++
            Map("Stage" -> parameters.stage.name)
          LookupByTags(withStackAndStageTags)
        } else {
          val stackName = stack.nameOption.filter(_ => prependStackToCloudFormationStackName(pkg))
          val stageName = Some(parameters.stage.name).filter(_ => appendStageToCloudFormationStackName(pkg))
          val cloudFormationStackNameParts = Seq(stackName, Some(cloudFormationStackName(pkg)), stageName).flatten
          val fullCloudFormationStackName = cloudFormationStackNameParts.mkString("-")
          LookupByName(fullCloudFormationStackName)
        }
      }

      List(
        UpdateAmiCloudFormationParameterTask(
          cloudFormationStackLookupStrategy,
          amiParameter(pkg),
          amiTags(pkg),
          lookup.getLatestAmi,
          parameters.stage,
          stack
        ),
        CheckUpdateEventsTask(cloudFormationStackLookupStrategy)
      )
    }
  }
}