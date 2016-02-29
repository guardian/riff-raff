package resources

import deployment.DeployInfoManager
import magenta._
import org.joda.time.DateTime
import com.gu.management.DefaultSwitch
import conf.Configuration

object LookupSelector {
  lazy val switches = Seq(enablePrism)

  lazy val enablePrism = new DefaultSwitch(
    "enable-prism-lookup",
    "When on, Riff-Raff will use Prism to lookup instances and data. When off, Riff-Raff will use the in built deployinfo discovery mechanism",
    conf.Configuration.lookup.source match {
      case "deployinfo" => false
      case "prism" => true
      case sourceName => throw new IllegalArgumentException(s"Lookup source $sourceName is not known")
    }
  )

  lazy val secretProvider = new SecretProvider {
    def lookup(service: String, account: String): Option[String] =
      conf.Configuration.credentials.lookupSecret(service, account)

    def sshCredentials = SystemUser(keyFile = Configuration.sshKey.file)
  }

  def deployInfoLookup = new Lookup {
    val lookupDelegate = DeployInfoLookupShim(DeployInfoManager.deployInfo, secretProvider)
    val name = "DeployInfo riffraff shim"
    def lastUpdated: DateTime = lookupDelegate.lastUpdated
    def hosts: HostLookup = lookupDelegate.hosts
    def data: DataLookup = lookupDelegate.data
    def stages = lookupDelegate.stages.sorted(conf.Configuration.stages.ordering).reverse
    def keyRing(stage: Stage, apps: Set[App], stack: Stack) = lookupDelegate.keyRing(stage, apps, stack)
    def getLatestAmi(region: String)(tags: Map[String, String]): Option[String] = lookupDelegate.getLatestAmi(region)(tags)
  }

  def apply():Lookup = {
    if (enablePrism.isSwitchedOn) PrismLookup
    else deployInfoLookup
  }
}