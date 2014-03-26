package magenta.deployment_type

import magenta.tasks.{PurgeFromFastly, UpdateFastlyConfig}

object Fastly  extends DeploymentType {
  val name = "fastly"
  val documentation =
    """
      |Deploy a new set of VCL configuration files to the [fastly](http://www.fastly.com/) CDN via the fastly API.
    """.stripMargin

  def perAppActions = {
    case "deploy" => pkg => (lookup, parameters, stack) => {
      implicit val keyRing = lookup.keyRing(parameters.stage, pkg.apps.toSet, stack)
      List(UpdateFastlyConfig(pkg))
    }
  }
}

object FastlyPurge extends DeploymentType {
  val urls = Param[List[String]]("urls")

  def perAppActions = {
    case "purge" => pkg => (_, _) => List(PurgeFromFastly(urls(pkg)))
  }

  def documentation =
    """
      |Purges the files specified from the [fastly](http://www.fastly.com/) CDN caches via their API
    """.stripMargin

  def name = "fastly-purge"
}
