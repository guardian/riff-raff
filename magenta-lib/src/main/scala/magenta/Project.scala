package magenta

import json.DeployInfoJsonInputFile
import tasks.Task
import collection.SortedSet

object DeployInfo {
  def apply(jsonData: DeployInfoJsonInputFile): DeployInfo = {
    val magentaHosts = jsonData.hosts map { host => Host(host.hostname, Set(App(host.app)), host.stage) }
    val magentaKeys = jsonData.keys map { key => Key(key.app, key.stage, key.accesskey, key.comment) }
    DeployInfo(magentaHosts, magentaKeys)
  }
}

case class DeployInfo(hosts: List[Host], keys: List[Key]) {
  def knownHostStages: List[String] = hosts.map(_.stage).distinct.sorted
  def knownHostApps: List[Set[App]] = hosts.map(_.apps).distinct.sortWith(_.toList.head.name < _.toList.head.name)
  def knownKeyStages: List[String] =  keys.map(_.stage).distinct.sortWith(_.toString < _.toString)
  def knownKeyApps: List[String] = keys.map(_.app).distinct.sortWith(_.toString < _.toString)
  def stageAppToHostMap: Map[(String,Set[App]),List[Host]] = hosts.groupBy(host => (host.stage,host.apps))
  def stageAppToKeyMap: Map[(String,String),List[Key]] = keys.groupBy(key => (key.stage,key.app))
  def firstMatchingKey(app:App, stage:String): Option[Key] = {
    keys.find(key => key.appRegex.findFirstMatchIn(app.name).isDefined && key.stageRegex.findFirstMatchIn(stage).isDefined)
  }
}

case class Host(
    name: String,
    apps: Set[App] = Set.empty,
    stage: String = "NO_STAGE",
    connectAs: Option[String] = None)
{
  def app(name: String) = this.copy(apps = apps + App(name))
  def app(app: App) = this.copy(apps= apps + app)

  def as(user: String) = this.copy(connectAs = Some(user))

  // this allows "resin" @: Host("some-host")
  def @:(user: String) = as(user)

  lazy val connectStr = (connectAs map { _ + "@" } getOrElse "") + name
}

case class Key(
  app: String,
  stage: String,
  key: String,
  comment: Option[String]
) {
  lazy val appRegex = ("^%s$" format app).r
  lazy val stageRegex = ("^%s$" format stage).r
}

case class HostList(hosts: List[Host]) {
  def supportedApps = {
    val apps = for {
      host <- hosts
      app <- host.apps
    } yield app.name
    SortedSet(apps: _*).mkString(", ")
  }

  def dump = hosts
    .sortBy { _.name }
    .map { h => " %s: %s" format (h.name, h.apps.map { _.name } mkString ", ") }
    .mkString("\n")

  def filterByStage(stage: Stage): HostList = new HostList(hosts.filter(_.stage == stage.name))
}
object HostList {
  implicit def listOfHostsAsHostList(hosts: List[Host]) = new HostList(hosts)
  implicit def hostListAsListOfHosts(hostList: HostList) = hostList.hosts
}

/*
 An action represents a step within a recipe. It isn't executable
 until it's resolved against a particular host.
 */
trait Action {
  def apps: Set[App]
  def description: String
}

trait PerHostAction extends Action {
  def resolve(host: Host): List[Task]
}

trait PerAppAction extends Action {
  def resolve(stage: Stage): List[Task]
}

case class App(name: String)

case class Recipe(
  name: String,
  actionsBeforeApp: Iterable[Action] = Nil, //executed once per app (before the host actions are executed)
  actionsPerHost: Iterable[Action] = Nil,  //executed once per host in the application
  dependsOn: List[String] = Nil
)

case class Project(
  packages: Map[String, Package] = Map.empty,
  recipes: Map[String, Recipe] = Map.empty
) {
  lazy val applications = packages.values.flatMap(_.apps).toSet
}

case class Stage(name: String)
case class Build(name:String, id:String)
case class RecipeName(name:String)

case class Deployer(name: String)

case class DeployParameters(deployer: Deployer, build: Build, stage: Stage, recipe: RecipeName = RecipeName("default"), hostList: List[String] = Nil) {
  def toDeployContext(project: Project, hosts:HostList): DeployContext = {
    val filteredHosts:HostList = if (hostList.isEmpty) hosts else hosts.filter(hostList contains _.name)
    DeployContext(this,project,filteredHosts)
  }
}
