package resources

import deployment.DeployInfoManager
import magenta._
import org.joda.time.DateTime
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
      new Lookup {
        val lookupDelegate = DeployInfoLookupShim(
          DeployInfoManager.deployInfo,
          new SecretProvider {
            def lookup(service: String, account: String): Option[String] =
              conf.Configuration.credentials.lookupSecret(service, account)
          }
        )
        def lastUpdated: DateTime = lookupDelegate.lastUpdated
        def instances: Instances = lookupDelegate.instances
        def data: Data = lookupDelegate.data
        def stages = lookupDelegate.stages.sorted(conf.Configuration.stages.ordering).reverse
        def credentials(stage: Stage, apps: Set[App]) = lookupDelegate.credentials(stage, apps)
      }
    }
}