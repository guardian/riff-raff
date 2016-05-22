package magenta.deployment_type

import magenta.DeployLogger$
import magenta.tasks.UpdateFastlyConfig

object Fastly  extends DeploymentType {
  val name = "fastly"
  val documentation =
    """
      |Deploy a new set of VCL configuration files to the [fastly](http://www.fastly.com/) CDN via the fastly API.
    """.stripMargin

  def perAppActions = {
    case "deploy" => pkg => (logger, lookup, parameters, stack) => {
      implicit val keyRing = lookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      logger.verbose(s"Keyring is $keyRing")
      List(UpdateFastlyConfig(pkg))
    }
  }
}
