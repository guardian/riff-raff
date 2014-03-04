package magenta

import json.{DeployInfoHost, DeployInfoJsonInputFile}
import tasks.Task
import collection.SortedSet
import java.util.UUID
import org.joda.time.DateTime

object DeployInfo {
  def apply(): DeployInfo = DeployInfo(DeployInfoJsonInputFile(Nil,None,Map.empty), None)
}

case class DeployInfo(input:DeployInfoJsonInputFile, createdAt:Option[DateTime]) {

  def asHost(host: DeployInfoHost) = {
    val tags:List[(String,String)] =
      List("group" -> host.group) ++
        host.created_at.map("created_at" -> _) ++
        host.dnsname.map("dnsname" -> _) ++
        host.instancename.map("instancename" -> _) ++
        host.internalname.map("internalname" -> _)
    Host(host.hostname, Set(LegacyApp(host.app)), host.stage, tags = tags.toMap)
  }

  def forParams(params: DeployParameters): DeployInfo = {
    if (params.hostList.isEmpty) filterHosts(_.stage == params.stage.name)
    else filterHosts(params.hostList contains _.name)
  }

  def filterHosts(p: Host => Boolean) = this.copy(input = input.copy(hosts = input.hosts.filter(jsonHost => p(asHost(jsonHost)))))

  val hosts = input.hosts.map(asHost)
  val data = input.data mapValues { dataList =>
    dataList.map { data => Datum(None, data.app, data.stage, data.value, data.comment) }
  }

  lazy val knownHostStages: List[String] = hosts.map(_.stage).distinct.sorted
  lazy val knownHostApps: List[Set[App]] = hosts.map(_.apps).distinct.sortWith(_.toList.head.toString < _.toList.head.toString)

  def knownHostApps(stage: String): List[Set[App]] = knownHostApps.filter(stageAppToHostMap.contains(stage, _))

  lazy val knownKeys: List[String] = data.keys.toList.sorted

  def dataForKey(key: String): List[Datum] = data.get(key).getOrElse(List.empty)
  def knownDataStages(key: String) = data.get(key).toList.flatMap {_.map(_.stage).distinct.sortWith(_.toString < _.toString)}
  def knownDataApps(key: String): List[String] = data.get(key).toList.flatMap{_.map(_.app).distinct.sortWith(_.toString < _.toString)}

  lazy val stageAppToHostMap: Map[(String,Set[App]),Seq[Host]] =
    hosts.groupBy(host => (host.stage,host.apps)).mapValues(_.transposeBy(_.tags.getOrElse("group","")))

  def stageAppToDataMap(key: String): Map[(String,String),List[Datum]] =
    data.get(key).map {_.groupBy(key => (key.stage,key.app))}.getOrElse(Map.empty)

  def firstMatchingData(key: String, app:App, stage:String): Option[Datum] = {
    val matchingList = data.getOrElse(key, List.empty)
    app match {
      case LegacyApp(name) =>
        matchingList.filter(_.stack.isEmpty).find{data =>
          data.appRegex.findFirstMatchIn(name).isDefined && data.stageRegex.findFirstMatchIn(stage).isDefined
        }
      case StackApp(stackName, appName) =>
        matchingList.filter(_.stack.isDefined).find{data =>
          data.stackRegex.exists(_.findFirstMatchIn(appName).isDefined) &&
          data.appRegex.findFirstMatchIn(appName).isDefined &&
            data.stageRegex.findFirstMatchIn(stage).isDefined
        }
    }

  }
}

case class Host(
    name: String,
    apps: Set[App] = Set.empty,
    stage: String = "NO_STAGE",
    connectAs: Option[String] = None,
    tags: Map[String, String] = Map.empty)
{
  def app(app: App) = this.copy(apps = apps + app)

  def as(user: String) = this.copy(connectAs = Some(user))

  // this allows "resin" @: Host("some-host")
  def @:(user: String) = as(user)

  lazy val connectStr = (connectAs map { _ + "@" } getOrElse "") + name
}

case class Datum(
  stack: Option[String],
  app: String,
  stage: String,
  value: String,
  comment: Option[String]
) {
  lazy val stackRegex = stack.map(s => s"^$s$$".r)
  lazy val appRegex = ("^%s$" format app).r
  lazy val stageRegex = ("^%s$" format stage).r
}

case class HostList(hosts: List[Host]) {
  def dump = hosts
    .sortBy { _.name }
    .map { h => s" ${h.name}: ${h.apps.map(_.toString).mkString(", ")}" }
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
  def apps: Seq[App]
  def description: String
  def resolve(resourceLookup: Lookup, params: DeployParameters): List[Task]
}

trait App {}
object App {
  def apply(stack:Option[String], app:String): App = {
    stack.map{s =>
      StackApp(s, app)
    } getOrElse {
      LegacyApp(app)
    }
  }
  // takes a set of apps, assumed to be all the same type
  def stackAppTuple(apps: Set[App]): (Option[String], String) = {
    apps.headOption match {
      case Some(LegacyApp(_)) =>
        val appNames = apps.map{
          case LegacyApp(app) => app
        }.toSeq.sorted.mkString(", ")
        (None, appNames)
      case Some(StackApp(stack, _)) =>
        val appNames = apps.map{
          case StackApp(_, app) => app
        }.toSeq.sorted.mkString(", ")
        (Some(stack), appNames)
      case _ =>
        (None, "none")
    }
  }
}
case class LegacyApp(name: String) extends App {
  override lazy val toString: String = name
}
case class StackApp(stack: String, app:String) extends App {
  override lazy val toString: String = s"$stack::$app"
}

case class Recipe(
  name: String,
  actionsBeforeApp: Iterable[Action] = Nil, //executed once per app (before the host actions are executed)
  actionsPerHost: Iterable[Action] = Nil,  //executed once per host in the application
  dependsOn: List[String] = Nil
)

case class Project(
  packages: Map[String, DeploymentPackage] = Map.empty,
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

  def toDeployContext(uuid: UUID, project: Project, resourceLookup: Lookup): DeployContext = {
    DeployContext(this,project, resourceLookup, uuid)
  }
  def toDeployContext(project: Project, resourceLookup: Lookup): DeployContext = DeployContext(this,project, resourceLookup)
  def matchingHost(hostName:String) = hostList.isEmpty || hostList.contains(hostName)
}
