package magenta.tasks

import magenta.{Host, DeployParameters, Package}

trait ApplicationAction extends ((Package, DeployParameters) => Task) {
  def description: String
}

trait HostAction extends ((Package, Host) => Task) {
  def description: String
}