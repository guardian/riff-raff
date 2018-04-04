package magenta

import org.joda.time.DateTime

trait DataLookup {
  def keys: Seq[String]
  def all: Map[String,Seq[Datum]]
  def get(key:String): Seq[Datum] = all.getOrElse(key, Nil)
  def datum(key: String, app: App, stage: Stage, stack: Stack): Option[Datum]
}

trait HostLookup {
  def all:Seq[Host]
  def get(pkg: DeploymentPackage, app: App, parameters: DeployParameters, stack: Stack):Seq[Host]
}

trait Lookup {
  def name: String
  def lastUpdated: DateTime
  def hosts: HostLookup
  def stages: Seq[String]
  def data: DataLookup
  def keyRing(stage: Stage, app: App, stack: Stack): KeyRing
  def getLatestAmi(accountNumber: Option[String], tagFilter: Map[String, String] => Boolean)(region: String)(tags: Map[String, String]): Option[String]
}

trait SecretProvider {
  def lookup(service: String, account: String): Option[String]
}

