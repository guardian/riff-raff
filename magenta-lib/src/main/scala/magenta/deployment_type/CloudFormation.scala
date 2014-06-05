package magenta.deployment_type

import magenta.tasks.{CheckUpdateEventsTask, UpdateCloudFormationTask}
import scalax.file.Path

object CloudFormation extends DeploymentType {
  val name = "cloud-formation"
  def documentation = "Deploy an AWS CloudFormation template"

  val stackName = Param[String]("stackName").defaultFromPackage(_.name)
  val appendStageToStackName = Param[Boolean]("appendStageToStackName").default(true)
  val templatePath = Param[String]("templatePath").default("""cloud-formation\cfn.json""")
  val templateParameters = Param[Map[String, String]]("templateParameters").default(Map.empty)

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