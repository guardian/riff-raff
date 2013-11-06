package magenta.tasks

import magenta.{Host, DeployParameters, Package}

trait ApplicationTaskType extends ((Package, DeployParameters) => Task) {
  def description: String
}

trait HostTaskType extends ((Package, Host) => Task) {
  def description: String
}