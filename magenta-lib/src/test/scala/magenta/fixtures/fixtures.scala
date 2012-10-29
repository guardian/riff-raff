package magenta
package fixtures

import tasks.Task

case class StubTask(description: String, override val taskHost: Option[Host] = None) extends Task {
  def execute(keyRing: KeyRing) { }
  def verbose = "stub(%s)" format description
}

case class StubPerHostAction(description: String, apps: Set[App]) extends Action {
  def resolve(deployInfo: DeployInfo, params: DeployParameters) = throw new UnsupportedOperationException
}

case class StubPerAppAction(description: String, apps: Set[App]) extends Action {
  def resolve(deployInfo: DeployInfo, params: DeployParameters) = throw new UnsupportedOperationException
}

case class StubPackageType(override val perHostActions: PartialFunction[String, Host => List[Task]] = Map.empty,
                           override val perAppActions: PartialFunction[String, DeployParameters => List[Task]] = Map.empty,
                           pkg: Package=stubPackage()) extends PackageType {
  def name = "stub-package-type"
}

