package magenta.deployment_type

import magenta.tasks.{CheckUpdateEventsTask, UpdateCloudFormationTask}
import scalax.file.Path

object CloudFormation extends DeploymentType {
  val name = "cloud-formation"
  def documentation = "Update an AWS CloudFormation template"

  val stackName = Param[String]("stackName",
    documentation = "The name of the CloudFormation stack to update"
  ).defaultFromPackage(_.name)
  val appendStageToStackName = Param[Boolean]("appendStageToStackName",
    documentation = "Whether to add '-`stack`' to the `stackName`, e.g. MyApp => MyApp-PROD"
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

      val cfStack = if (appendStageToStackName(pkg)) s"${stackName(pkg)}-${parameters.stage.name}"
        else stackName(pkg)

      List(
        UpdateCloudFormationTask(
          cfStack,
          Path(pkg.srcDir) \ Path.fromString(templatePath(pkg)),
          templateParameters(pkg),
          parameters.stage
        ),
        CheckUpdateEventsTask(cfStack)
      )
    }
  }
}