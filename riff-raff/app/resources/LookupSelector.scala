package resources

import magenta.resources.Lookup
import deployment.DeployInfoManager
import com.gu.management.DefaultSwitch

object LookupSelector {
  lazy val enablePrism = new DefaultSwitch(
    "enable-prism-lookup",
    "When on, Riff-Raff will use Prism to lookup instances and data. When off, Riff-Raff will use the inbuild deployinfo discovery mechanism",
    conf.Configuration.lookup.source match {
      case "deployinfo" => false
      case "prism" => true
      case sourceName => throw new IllegalArgumentException(s"Lookup source $sourceName is not known")
    }
  )

  def apply():Lookup =
    if (enablePrism.isSwitchedOn) {
      PrismLookup
    } else {
      DeployInfoManager.deployInfo.asLookup
    }
}