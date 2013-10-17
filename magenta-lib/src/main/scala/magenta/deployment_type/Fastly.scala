package magenta.deployment_type

import magenta.tasks.UpdateFastlyConfig

object Fastly  extends DeploymentType {
  val name = "fastly"

  def perAppActions = {
    case "deploy" => pkg => (_, parameters) => List(UpdateFastlyConfig(pkg))
  }

  def params = Seq()
}
