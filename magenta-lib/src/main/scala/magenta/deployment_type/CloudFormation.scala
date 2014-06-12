package magenta.deployment_type

import magenta.{UnnamedStack, NamedStack}
import magenta.tasks.{CheckUpdateEventsTask, UpdateCloudFormationTask}
import scalax.file.Path

object CloudFormation extends DeploymentType {
  val name = "cloud-formation"
  def documentation = "Update an AWS CloudFormation template"

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
    documentation = "Map of parameter names and values to be passed into template"
  ).default(Map.empty)

  override def perAppActions = {
    case "updateStack" => pkg => (lookup, parameters, stack) => {
      implicit val keyRing = lookup.keyRing(parameters.stage, pkg.apps.toSet, stack)

      val stackName = stack.nameOption.filter(_ => prependStackToCloudFormationStackName(pkg))
      val stageName = Some(parameters.stage.name).filter(_ => appendStageToCloudFormationStackName(pkg))
      val cloudFormationStackNameParts = Seq(stackName, Some(cloudFormationStackName(pkg)), stageName).flatten
      val fullCloudFormationStackName = cloudFormationStackNameParts.mkString("-")

      List(
        UpdateCloudFormationTask(
          fullCloudFormationStackName,
          Path(pkg.srcDir) \ Path.fromString(templatePath(pkg)),
          templateParameters(pkg),
          parameters.stage,
          stack
        ),
        CheckUpdateEventsTask(fullCloudFormationStackName)
      )
    }
  }
}