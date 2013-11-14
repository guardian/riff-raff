package magenta.tasks

import magenta.{Host, DeployParameters, DeploymentPackage}

trait ApplicationTaskType extends ((DeploymentPackage, DeployParameters) => Task) {
  def description: String
}

trait HostTaskType extends ((DeploymentPackage, Host) => Task) {
  def description: String
}