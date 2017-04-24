package magenta.deployment_type

import magenta.tasks.UpdateFastlyConfig

object Fastly extends DeploymentType {
  val name = "fastly"
  val documentation =
    """
      |Deploy a new set of VCL configuration files to the [fastly](http://www.fastly.com/) CDN via the fastly API.
    """.stripMargin

  val deploy = Action(
    "deploy",
    """
      |Undertakes the following using the fastly API:
      |
      | - Clone the currently active version
      | - Deletes all of the existing files from the new clone
      | - Uploads files from this deployment
      | - Waits for it to compile
      | - Validates the config
      | - Activates the config
      |
      | This will set `main.vcl` as the main entrypoint (without checking that this exists) so make sure to structure
      | your VCL files to facilitate this.
    """.stripMargin
  ) { (pkg, resources, target) =>
    implicit val keyRing = resources.assembleKeyring(target, pkg)
    implicit val artifactClient = resources.artifactClient
    resources.reporter.verbose(s"Keyring is $keyRing")
    List(UpdateFastlyConfig(pkg.s3Package)(keyRing, artifactClient))
  }

  def defaultActions = List(deploy)
}
