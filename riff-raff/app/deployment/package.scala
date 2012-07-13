package deployment

import magenta.DeployParameters

object `package` {
  implicit def deployParameters2ToDeploymentKey(p: DeployParameters) = new {
    def toDeploymentKey: DeploymentKey = DeploymentKey(p.stage.name, p.build.name, p.build.id, p.recipe.name)
  }
}