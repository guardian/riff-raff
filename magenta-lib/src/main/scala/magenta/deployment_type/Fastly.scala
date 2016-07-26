package magenta.deployment_type

import magenta.tasks.UpdateFastlyConfig

object Fastly  extends DeploymentType {
  val name = "fastly"
  val documentation =
    """
      |Deploy a new set of VCL configuration files to the [fastly](http://www.fastly.com/) CDN via the fastly API.
    """.stripMargin

  def perAppActions = {
    case "deploy" => pkg => (resources, target) => {
      implicit val keyRing = resources.assembleKeyring(target, pkg)
      implicit val artifactClient = resources.artifactClient
      resources.reporter.verbose(s"Keyring is $keyRing")
      List(UpdateFastlyConfig(pkg.s3Package)(keyRing, artifactClient))
    }
  }
}
