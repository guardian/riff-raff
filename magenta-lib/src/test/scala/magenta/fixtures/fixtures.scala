package magenta
package fixtures

import magenta.deployment_type.{DeploymentType, Param, ParamRegister}
import magenta.tasks.Task

case class StubTask(description: String, region: Region, stack: Option[Stack] = None,
  override val taskHost: Option[Host] = None) extends Task {

  def execute(reporter: DeployReporter, stopFlag: => Boolean) { }
  def verbose = "stub(%s)" format description
  def keyRing = KeyRing()
}

case class StubPerAppAction(description: String, apps: Seq[App]) extends Action {
  def resolve(resources: DeploymentResources, target: DeployTarget) = ???
}

case class StubDeploymentType(
  override val actions:
    PartialFunction[String, DeploymentPackage => (DeploymentResources, DeployTarget) => List[Task]] = Map.empty,
  override val defaultActions: List[String],
  parameters: ParamRegister => List[Param[_]] = _ => Nil
) extends DeploymentType {
  parameters(register)

  def name = "stub-package-type"

  val documentation = "Documentation for the testing stub"
}
