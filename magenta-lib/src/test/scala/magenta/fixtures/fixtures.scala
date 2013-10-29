package magenta
package fixtures

import tasks.Task
import magenta.deployment_type.DeploymentType
import magenta.Lookup

case class StubTask(description: String, override val taskHost: Option[Host] = None) extends Task {
  def execute(keyRing: KeyRing, stopFlag: =>  Boolean) { }
  def verbose = "stub(%s)" format description
}

case class StubPerHostAction(description: String, apps: Set[App]) extends Action {
  def resolve(resourceLookup: Lookup, params: DeployParameters) = throw new UnsupportedOperationException
}

case class StubPerAppAction(description: String, apps: Set[App]) extends Action {
  def resolve(resourceLookup: Lookup, params: DeployParameters) = throw new UnsupportedOperationException
}

case class StubDeploymentType(override val perHostActions:
                            PartialFunction[String, Package => Host => List[Task]] = Map.empty,
                           override val perAppActions:
                            PartialFunction[String, Package => (Lookup, DeployParameters) => List[Task]] = Map.empty
                            ) extends DeploymentType {
  def name = "stub-package-type"

  val documentation = "Documentation for the testing stub"
}

