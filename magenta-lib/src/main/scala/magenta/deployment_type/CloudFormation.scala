package magenta.deployment_type

import magenta.tasks.UpdateCloudFormationTask
import scalax.file.Path

object CloudFormation extends DeploymentType {
  val name = "cloud-formation"
  def documentation = "Deploy an AWS CloudFormation template"

  val stackName = Param[String]("stackName").defaultFromPackage(_.name)
  val appendStageToStackName = Param[Boolean]("appendStageToStackName").default(true)
  val templatePath = Param[String]("templatePath").default("""cloud-formation\cfn.json""")
  val templateParameters = Param[Map[String, String]]("templateParameters").default(Map.empty)

  override def perAppActions = {
    case "updateStack" => pkg => (_, parameters, _) => List(
      UpdateCloudFormationTask(
        if (appendStageToStackName(pkg)) s"${stackName(pkg)}-${parameters.stage.name}" else stackName(pkg),
        Path(pkg.srcDir) \ Path.fromString(templatePath(pkg)),
        templateParameters(pkg),
        parameters.stage
      )
    )
  }
}