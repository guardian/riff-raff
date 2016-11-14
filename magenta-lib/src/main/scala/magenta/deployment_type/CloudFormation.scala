package magenta.deployment_type

import magenta.artifact.S3Path
import magenta.tasks.{CheckUpdateEventsTask, UpdateCloudFormationTask}
import magenta.tasks.UpdateCloudFormationTask.LookupByName

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
      |
      |If your CloudFormation template is in YAML format, it will be automatically converted to JSON
      |before it is used.
    """.stripMargin

  val cloudFormationStackName = Param[String]("cloudFormationStackName",
    documentation = "The name of the CloudFormation stack to update"
  ).defaultFromContext((pkg, _) => Right(pkg.name))
  val prependStackToCloudFormationStackName = Param[Boolean]("prependStackToCloudFormationStackName",
    documentation = "Whether to prepend '`stack`-' to the `cloudFormationStackName`, e.g. MyApp => service-preview-MyApp"
  ).default(true)
  val appendStageToCloudFormationStackName = Param[Boolean]("appendStageToCloudFormationStackName",
    documentation = "Whether to add '-`stage`' to the `cloudFormationStackName`, e.g. MyApp => MyApp-PROD"
  ).default(true)
  val templatePath = Param[String]("templatePath",
    documentation = "Location of template to use within package. If it has a standard YAML file extension (`.yml` or `.yaml`), the template will be converted from YAML to JSON."
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
    documentation = "Specify the set of tags to use to find the latest AMI"
  ).default(Map.empty)
  val amiParameter = Param[String]("amiParameter",
    documentation = "The CloudFormation parameter name for the AMI"
  ).default("AMI")

  def defaultActions = List("updateStack")

  override def actions = {
    case "updateStack" => pkg => (resources, target) => {
      implicit val keyRing = resources.assembleKeyring(target, pkg)
      implicit val artifactClient = resources.artifactClient
      val reporter = resources.reporter

      val stackName = target.stack.nameOption.filter(_ => prependStackToCloudFormationStackName(pkg, target, reporter))
      val stageName = Some(target.parameters.stage.name).filter(_ => appendStageToCloudFormationStackName(pkg, target, reporter))
      val cloudFormationStackNameParts = Seq(stackName, Some(cloudFormationStackName(pkg, target, reporter)), stageName).flatten
      val fullCloudFormationStackName = cloudFormationStackNameParts.mkString("-")

      val globalParams = templateParameters(pkg, target, reporter)
      val stageParams = templateStageParameters(pkg, target, reporter).lift.apply(target.parameters.stage.name).getOrElse(Map())
      val params = globalParams ++ stageParams

      List(
        UpdateCloudFormationTask(
          fullCloudFormationStackName,
          S3Path(pkg.s3Package, templatePath(pkg, target, reporter)),
          params,
          amiParameter(pkg, target, reporter),
          amiTags(pkg, target, reporter),
          resources.lookup.getLatestAmi,
          target.parameters.stage,
          target.stack,
          createStackIfAbsent(pkg, target, reporter)
        ),
        CheckUpdateEventsTask(LookupByName(fullCloudFormationStackName))
      )
    }
  }
}