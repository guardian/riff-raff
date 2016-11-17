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

  val cloudformationStackByTags = Param[Boolean]("cloudFormationStackByTags",
    documentation = "Whether to find the cloudFormationStack by name or by tags"
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
  )
  val amiParameter = Param[String]("amiParameter",
    documentation = "The CloudFormation parameter name for the AMI"
  ).default("AMI")

  val update = Action("update",
    """
      |Given AMI tags, this will resolve the latest matching AMI and update the AMI parameter
      | on the provided CloudFormation stack.
    """.stripMargin
  ){ (pkg, resources, target) => {
      implicit val keyRing = resources.assembleKeyring(target, pkg)
      val reporter = resources.reporter

      val cloudFormationStackLookupStrategy = {
        if (cloudformationStackByTags(pkg, target, reporter)) {
          // todo: this can be simplified once the legacy json format is removed
          val lookupByTags = for {
            stack <- target.stack.nameOption
            app <- pkg.pkgApps.map(_.name).headOption if pkg.pkgApps.size == 1
            stage = target.parameters.stage.name
          } yield LookupByTags(Map(
            "Stage" -> stage,
            "Stack" -> stack,
            "App" -> app
          ))

          lookupByTags.getOrElse(reporter.fail(
            s"The $name package type can only be used when the stack and exactly one app is specified - you have stage=${target.stack.nameOption} and apps=${pkg.apps.map(_.name).mkString(",")}"
          ))
        } else {
          val stackName = target.stack.nameOption.filter(_ => prependStackToCloudFormationStackName(pkg, target, reporter))
          val stageName = Some(target.parameters.stage.name).filter(_ => appendStageToCloudFormationStackName(pkg, target, reporter))
          val cloudFormationStackNameParts = Seq(stackName, Some(cloudFormationStackName(pkg, target, reporter)), stageName).flatten
          val fullCloudFormationStackName = cloudFormationStackNameParts.mkString("-")
          LookupByName(fullCloudFormationStackName)
        }
      }

      List(
        UpdateAmiCloudFormationParameterTask(
          cloudFormationStackLookupStrategy,
          amiParameter(pkg, target, reporter),
          amiTags(pkg, target, reporter),
          resources.lookup.getLatestAmi,
          target.parameters.stage,
          target.stack
        ),
        CheckUpdateEventsTask(cloudFormationStackLookupStrategy)
      )
    }
  }

  def defaultActions = List(update)
}