package magenta
package fixtures

import magenta.deployment_type._
import magenta.tasks.Task

case class StubTask(description: String, region: Region, stack: Option[Stack] = None,
  override val taskHost: Option[Host] = None) extends Task {

  def execute(reporter: DeployReporter, stopFlag: => Boolean) { }
  def verbose = "stub(%s)" format description
  def keyRing = KeyRing()
}

case class StubPerAppDeploymentStep(description: String, apps: Seq[App]) extends DeploymentStep {
  def resolve(resources: DeploymentResources, target: DeployTarget) = ???
}

object StubActionRegister extends ActionRegister {
  def add(action: Action): Unit = {}
}

case class StubDeploymentType(
  override val actionsMap: Map[String, Action] = Map.empty,
  override val defaultActionNames: List[String],
  parameters: ParamRegister => List[Param[_]] = _ => Nil,
  override val name: String = "stub-package-type"
) extends DeploymentType {
  parameters(paramsRegister)


  def defaultActions: List[Action] = defaultActionNames.map { name => actionsMap(name) }

  val documentation = "Documentation for the testing stub"
}
