package magenta
package fixtures

import magenta.deployment_type.DeploymentType
import magenta.tasks.Task

case class StubTask(description: String, override val taskHost: Option[Host] = None, stack: Option[Stack] = None) extends Task {
  def execute(reporter: DeployReporter, stopFlag: => Boolean) { }
  def verbose = "stub(%s)" format description
  def keyRing = KeyRing()
}

case class StubPerHostAction(description: String, apps: Seq[App]) extends Action {
  def resolve(resources: DeploymentResources, target: DeployTarget) = ???
}

case class StubPerAppAction(description: String, apps: Seq[App]) extends Action {
  def resolve(resources: DeploymentResources, target: DeployTarget) = ???
}

case class StubDeploymentType(
  override val perHostActions:
    PartialFunction[String, DeploymentPackage => (DeployReporter, Host, KeyRing) => List[Task]] = Map.empty,
  override val perAppActions:
    PartialFunction[String, DeploymentPackage => (DeploymentResources, DeployTarget) => List[Task]] = Map.empty
                            ) extends DeploymentType {
  def name = "stub-package-type"

  val documentation = "Documentation for the testing stub"
}

