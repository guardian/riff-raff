package magenta.deployment_type

import magenta.tasks.UpdateCloudFormationTask
import scalax.file.Path

object CloudFormationType extends DeploymentType {
  val name = "cloud-formation"
  def documentation = "Deploy an AWS CloudFormation template"

  val stackName = Param[String]("stackName").defaultFromPackage(_.name)
  val templatePath = Param[String]("templatePath").default("""cloud-formation\cfn.json""")

  override def perAppActions = {
    case "updateStack" => pkg => (_, parameters) => List(
      UpdateCloudFormationTask(
        stackName(pkg),
        Path(pkg.srcDir) \ Path.fromString(templatePath(pkg))
      )
    )
  }
}