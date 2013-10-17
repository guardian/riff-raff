package magenta
package fixtures

import tasks.Task
import magenta.packages.PackageType

case class StubTask(description: String, override val taskHost: Option[Host] = None) extends Task {
  def execute(keyRing: KeyRing, stopFlag: =>  Boolean) { }
  def verbose = "stub(%s)" format description
}

case class StubPerHostAction(description: String, apps: Set[App]) extends Action {
  def resolve(deployInfo: DeployInfo, params: DeployParameters) = throw new UnsupportedOperationException
}

case class StubPerAppAction(description: String, apps: Set[App]) extends Action {
  def resolve(deployInfo: DeployInfo, params: DeployParameters) = throw new UnsupportedOperationException
}

case class StubPackageType(override val perHostActions:
                            PartialFunction[String, Package => Host => List[Task]] = Map.empty,
                           override val perAppActions:
                            PartialFunction[String, Package => (DeployInfo, DeployParameters) => List[Task]] = Map.empty
                            ) extends PackageType {
  def name = "stub-package-type"
  def params = Seq()
}

