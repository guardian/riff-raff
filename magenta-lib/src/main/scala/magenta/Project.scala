package magenta

import json.DeployInfoJsonInputFile
import tasks.Task
import collection.SortedSet
import java.util.UUID

object DeployInfo {
  def apply(jsonData: DeployInfoJsonInputFile): DeployInfo = {
    val magentaHosts = jsonData.hosts map { host =>
      val tags:List[(String,String)] =
        List("group" -> host.group) ++
          host.created_at.map("created_at" -> _) ++
          host.dnsname.map("dnsname" -> _) ++
          host.instancename.map("instancename" -> _) ++
          host.internalname.map("internalname" -> _)
      Host(host.hostname, Set(App(host.app)), host.stage, tags = tags.toMap)
    }
    val magentaData = jsonData.data mapValues { dataList =>
      dataList.map { data => Data(data.app, data.stage, data.value, data.comment) }
    }
    DeployInfo(magentaHosts, magentaData, Some(jsonData))
  }
}

case class DeployInfo(hosts: List[Host], data: Map[String,List[Data]] = Map(), input:Option[DeployInfoJsonInputFile] = None) {
  import HostList._
  def forParams(params: DeployParameters) = {
    val hostsFilteredByStage = hosts.filterByStage(params.stage).hosts
    val hostsForParams =
      if (params.hostList.isEmpty)
        hostsFilteredByStage
      else
        hosts.filter(params.hostList contains _.name)

    DeployInfo(hostsForParams, data)
  }

  def knownHostStages: List[String] = hosts.map(_.stage).distinct.sorted
  def knownHostApps: List[Set[App]] = hosts.map(_.apps).distinct.sortWith(_.toList.head.name < _.toList.head.name)

  def knownHostApps(stage: String): List[Set[App]] = knownHostApps.filter(stageAppToHostMap.contains(stage, _))

  def knownKeys: List[String] = data.keys.toList.sorted

  def dataForKey(key: String): List[Data] = data.get(key).getOrElse(List.empty)
  def knownDataStages(key: String) = data.get(key).toList.flatMap {_.map(_.stage).distinct.sortWith(_.toString < _.toString)}
  def knownDataApps(key: String): List[String] = data.get(key).toList.flatMap{_.map(_.app).distinct.sortWith(_.toString < _.toString)}

  def stageAppToHostMap: Map[(String,Set[App]),List[Host]] = hosts.groupBy(host => (host.stage,host.apps))
  def stageAppToDataMap(key: String): Map[(String,String),List[Data]] = data.get(key).map {_.groupBy(key => (key.stage,key.app))}.getOrElse(Map.empty)

  def firstMatchingData(key: String, app:App, stage:String): Option[Data] = {
    val matchingList = data.getOrElse(key, List.empty)
    matchingList.find(data => data.appRegex.findFirstMatchIn(app.name).isDefined && data.stageRegex.findFirstMatchIn(stage).isDefined)
  }
}

case class Host(
    name: String,
    apps: Set[App] = Set.empty,
    stage: String = "NO_STAGE",
    connectAs: Option[String] = None,
    tags: Map[String, String] = Map.empty)
{
  def app(name: String) = this.copy(apps = apps + App(name))
  def app(app: App) = this.copy(apps= apps + app)

  def as(user: String) = this.copy(connectAs = Some(user))

  // this allows "resin" @: Host("some-host")
  def @:(user: String) = as(user)

  lazy val connectStr = (connectAs map { _ + "@" } getOrElse "") + name
}

case class Data(
  app: String,
  stage: String,
  value: String,
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
  implicit def listOfHostsAsHostList(hosts: List[Host]): HostList = new HostList(hosts)
  implicit def hostListAsListOfHosts(hostList: HostList) = hostList.hosts
}

/*
 An action represents a step within a recipe. It isn't executable
 until it's resolved against a particular host.
 */
trait Action {
  def apps: Set[App]
  def description: String
  def resolve(deployInfo: DeployInfo, params: DeployParameters): List[Task]
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
case class Build(projectName:String, id:String)
case class RecipeName(name:String)
object DefaultRecipe {
  def apply() = RecipeName("default")
}

case class Deployer(name: String)

case class DeployParameters(deployer: Deployer, build: Build, stage: Stage, recipe: RecipeName = DefaultRecipe(), hostList: List[String] = Nil) {
  def toDeployContext(uuid: UUID, project: Project, deployInfo: DeployInfo): DeployContext = {
    DeployContext(this,project, deployInfo, uuid)
  }
  def toDeployContext(project: Project, deployInfo: DeployInfo): DeployContext = DeployContext(this,project, deployInfo)
}
