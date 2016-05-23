package magenta
package fixtures

import tasks.Task
import magenta.deployment_type.DeploymentType
import magenta.Lookup

case class StubTask(description: String, override val taskHost: Option[Host] = None, stack: Option[Stack] = None) extends Task {
  def execute(logger: DeployLogger, stopFlag: =>  Boolean) { }
  def verbose = "stub(%s)" format description
  def keyRing = KeyRing(SystemUser(None))
}

case class StubPerHostAction(description: String, apps: Seq[App]) extends Action {
  def resolve(resourceLookup: Lookup, params: DeployParameters, stack: Stack, logger: DeployLogger) = ???
}

case class StubPerAppAction(description: String, apps: Seq[App]) extends Action {
  def resolve(resourceLookup: Lookup, params: DeployParameters, stack: Stack, logger: DeployLogger) = ???
}

case class StubDeploymentType(
  override val perHostActions:
    PartialFunction[String, DeploymentPackage => (DeployLogger, Host, KeyRing) => List[Task]] = Map.empty,
  override val perAppActions:
    PartialFunction[String, DeploymentPackage => (DeployLogger, Lookup, DeployParameters, Stack) => List[Task]] = Map.empty
                            ) extends DeploymentType {
  def name = "stub-package-type"

  val documentation = "Documentation for the testing stub"
}

