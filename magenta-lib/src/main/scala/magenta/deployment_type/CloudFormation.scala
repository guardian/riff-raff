package magenta.deployment_type

import magenta.KeyRing
import magenta.tasks.{CheckUpdateEventsTask, UpdateCloudFormationTask}

import scalax.file.Path

object CloudFormation extends DeploymentType {
  val name = "cloud-formation"
  def documentation =
    """Update an AWS CloudFormation template.
      |
      |It is strongly recommended you do _NOT_ set a desired-capacity on auto-scaling groups, managed
      |with CloudFormation templates deployed in this way, as otherwise any deployment will reset the
      |capacity to this number, even if scaling actions have triggered, changing the capacity, in the
      |mean-time.
      |
      |This deployment type is not currently recommended for continuous deployment, as CloudFormation
      |will fail if you try to update a CloudFormation stack with a configuration that matches its
      | current state.
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
  val templatePath = Param[String]("templatePath",
    documentation = "Location of template to use within package"
  ).default("""cloud-formation/cfn.json""")
  val templateParameters = Param[Map[String, String]]("templateParameters",
    documentation = "Map of parameter names and values to be passed into template. `Stage` and `Stack` (if `defaultStacks` are specified) will be appropriately set automatically."
  ).default(Map.empty)
  val templateStageParameters = Param[Map[String, Map[String, String]]]("templateStageParameters",
    documentation =
      """Like templateParameters, a map of parameter names and values, but in this case keyed by stage to
        |support stage-specific configuration. E.g.
        |
        |    {
        |        "CODE": { "apiUrl": "my.code.endpoint", ... },
        |        "PROD": { "apiUrl": "my.prod.endpoint", ... },
        |    }
        |
        |At deploy time, parameters for the matching stage (if found) are merged into any
        |templateParameters parameters, with stage-specific values overriding general parameters
        |when in conflict.""".stripMargin
  ).default(Map.empty)
  val createStackIfAbsent = Param[Boolean]("createStackIfAbsent",
    documentation = "If set to true then the cloudformation stack will be created if it doesn't already exist"
  ).default(true)
  val amiTags = Param[Map[String,String]]("amiTags",
    documentation =
    """
      |Specify the set of tags to use to find the latest AMI
    """.stripMargin
  ).default(Map.empty)
  val amiParameter = Param[String]("amiParameter",
    documentation =
    """
      |The CFN parameter to
    """.stripMargin
  ).default("AMI")

  def latestImage(tags: Map[String, String])(implicit keyRing: KeyRing): Option[String] = ???

  override def perAppActions = {
    case "updateStack" => pkg => (lookup, parameters, stack) => {
      implicit val keyRing = lookup.keyRing(parameters.stage, pkg.apps.toSet, stack)

      val stackName = stack.nameOption.filter(_ => prependStackToCloudFormationStackName(pkg))
      val stageName = Some(parameters.stage.name).filter(_ => appendStageToCloudFormationStackName(pkg))
      val cloudFormationStackNameParts = Seq(stackName, Some(cloudFormationStackName(pkg)), stageName).flatten
      val fullCloudFormationStackName = cloudFormationStackNameParts.mkString("-")

      val globalParams = templateParameters(pkg)
      val stageParams = templateStageParameters(pkg).lift.apply(parameters.stage.name).getOrElse(Map())
      val amiParam: Option[(String, String)] = if (amiTags(pkg).nonEmpty) {
        latestImage(amiTags(pkg)).map(amiParameter(pkg) ->)
      } else None
      val combinedParams = globalParams ++ stageParams ++ amiParam

      List(
        UpdateCloudFormationTask(
          fullCloudFormationStackName,
          Path(pkg.srcDir) \ Path.fromString(templatePath(pkg)),
          combinedParams,
          parameters.stage,
          stack,
          createStackIfAbsent(pkg)
        ),
        CheckUpdateEventsTask(fullCloudFormationStackName)
      )
    }
  }
}