package magenta
package fixtures

import tasks.Task

case class StubTask(description: String, override val taskHost: Option[Host] = None) extends Task {
  def execute(keyRing: KeyRing) { }
  def verbose = "stub(%s)" format description
}

case class StubPerHostAction(description: String, apps: Set[App]) extends Action {
  def resolve(host: Host) = StubTask(description + " per host task on " + host.name) :: Nil

  def resolve(deployInfo: DeployInfo, parameters: DeployParameters) = {
    val hostsForApps = deployInfo.hosts.filter(h => (h.apps intersect apps).nonEmpty)
    hostsForApps map (host => StubTask(description + " per host task on " + host.name, Some(host)))
  }
}

case class StubPerAppAction(description: String, apps: Set[App]) extends Action {
  def resolve(deployInfo: DeployInfo, params: DeployParameters) =
    StubTask(description + " per app task") :: Nil
}

object TestParams {
  def apply() = DeployParameters(
      Deployer("default deployer"),
      Build("default project", "default version"),
      Stage("test stage")
  )
}

case class StubPackageType(override val perHostActions: PartialFunction[String, Host => List[Task]] = Map.empty,
                           override val perAppActions: PartialFunction[String, DeployParameters => List[Task]] = Map.empty,
                           pkg: Package=StubPackage()) extends PackageType {
  def name = "stub-package-type"
}

object StubPackage{
  def apply() = Package("stub project", Set(), Map(), "stub-package-type", null)
}
