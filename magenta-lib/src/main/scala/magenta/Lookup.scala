package magenta

import org.joda.time.DateTime

trait Data {
  def keys: Seq[String]
  def all: Map[String,Seq[Datum]]
  def get(key:String): Seq[Datum] = all.get(key).getOrElse(Nil)
  def datum(key: String, app: App, stage: Stage, stack: Stack): Option[Datum]
}

trait Instances {
  def all:Seq[Host]
  def get(pkg: DeploymentPackage, app: App, parameters: DeployParameters, stack: Stack):Seq[Host]
}

trait Lookup {
  def name: String
  def lastUpdated: DateTime
  def instances: Instances
  def stages: Seq[String]
  def data: Data
  def keyRing(stage: Stage, apps: Set[App], stack: Stack): KeyRing
}

trait SecretProvider {
  def sshCredentials: SshCredentials
  def lookup(service: String, account: String): Option[String]
}

trait MagentaCredentials {
  def data: Data
  def secretProvider: SecretProvider
  def keyRing(stage: Stage, apps: Set[App], stack: Stack): KeyRing = KeyRing(
    sshCredentials = secretProvider.sshCredentials,
    apiCredentials = apps.toSeq.flatMap {
      app => {
        val KeyPattern = """credentials:(.*)""".r
        val apiCredentials = data.keys flatMap {
          case key@KeyPattern(service) =>
            data.datum(key, app, stage, stack).flatMap { data =>
              secretProvider.lookup(service, data.value).map { secret =>
                service -> ApiCredentials(service, data.value, secret, data.comment)
              }
            }
          case _ => None
        }
        apiCredentials
      }
    }.distinct.toMap
  )
}

case class DeployInfoLookupShim(deployInfo: DeployInfo, secretProvider: SecretProvider) extends Lookup with MagentaCredentials {
  val name = "DeployInfo shim"

  def lastUpdated: DateTime = deployInfo.createdAt.getOrElse(new DateTime(0L))

  def instances: Instances = new Instances {
    def get(pkg: DeploymentPackage, app: App, parameters: DeployParameters, stack: Stack): Seq[Host] = all.filter { host =>
      host.stage == parameters.stage.name &&
      host.apps.contains(app) &&
      Host.isValidForStack(host, stack)
    }
    def all: Seq[Host] = deployInfo.hosts
  }

  def data: Data = new Data {
    def keys: Seq[String] = deployInfo.knownKeys
    def all: Map[String, Seq[Datum]] = deployInfo.data
    def datum(key: String, app: App, stage: Stage, stack: Stack): Option[Datum] =
      deployInfo.firstMatchingData(key, app, stage, stack)
  }

  def stages = deployInfo.knownHostStages
}