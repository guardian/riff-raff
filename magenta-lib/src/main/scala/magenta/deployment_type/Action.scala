package magenta.deployment_type

import magenta.{DeployTarget, DeploymentPackage, DeploymentResources}
import magenta.tasks.Task

trait ActionRegister {
  def add(action: Action): Unit
}

case class Action(name: String, documentation: String = "_undocumented_")(
    val taskGenerator: (
        DeploymentPackage,
        DeploymentResources,
        DeployTarget
    ) => List[Task]
)(implicit register: ActionRegister) {
  register.add(this)
}
