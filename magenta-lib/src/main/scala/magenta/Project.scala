package magenta

import java.util.UUID

import magenta.json.{DeployInfoHost, DeployInfoJsonInputFile}
import magenta.tasks.Task
import org.joda.time.DateTime

import scala.math.Ordering.OptionOrdering

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
    Host(host.hostname, Set(App(host.app)), host.stage, host.stack, tags = tags.toMap)
  }

  def forParams(params: DeployParameters): DeployInfo = {
    if (params.hostList.isEmpty) filterHosts(_.stage == params.stage.name)
    else filterHosts(params.hostList contains _.name)
  }

  def filterHosts(p: Host => Boolean) = this.copy(input = input.copy(hosts = input.hosts.filter(jsonHost => p(asHost(jsonHost)))))

  val hosts = input.hosts.map(asHost)
  val data = input.data mapValues { dataList =>
    dataList.map { data => Datum(data.stack, data.app, data.stage, data.value, data.comment) }
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

  def firstMatchingData(key: String, app:App, stage: Stage, stack: Stack): Option[Datum] = {
    val matchingList = data.getOrElse(key, List.empty)
    stack match {
      case UnnamedStack =>
        matchingList.filter(_.stack.isEmpty).find{data =>
          data.appRegex.findFirstMatchIn(app.name).isDefined &&
          data.stageRegex.findFirstMatchIn(stage.name).isDefined
        }
      case NamedStack(stackName) =>
        matchingList.filter(_.stack.isDefined).find{data =>
          data.stackRegex.exists(_.findFirstMatchIn(stackName).isDefined) &&
          data.appRegex.findFirstMatchIn(app.name).isDefined &&
          data.stageRegex.findFirstMatchIn(stage.name).isDefined
        }
    }

  }
}

case class Host(
    name: String,
    apps: Set[App] = Set.empty,
    stage: String = "NO_STAGE",
    stack: Option[String] = None,
    connectAs: Option[String] = None,
    tags: Map[String, String] = Map.empty)
{
  def app(app: App) = this.copy(apps = apps + app)

  def as(user: String) = this.copy(connectAs = Some(user))

  // this allows "resin" @: Host("some-host")
  def @:(user: String) = as(user)

  lazy val connectStr = (connectAs map { _ + "@" } getOrElse "") + name

  def isValidForStack(s: Stack) = s match {
    case NamedStack(name) => stack.exists(_ == name)
    case UnnamedStack => true
  }
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

case class HostList(hosts: Seq[Host]) {
  def dump = hosts
    .sortBy { _.name }
    .map { h => s" ${h.name}: ${h.apps.map(_.toString).mkString(", ")}" }
    .mkString("\n")

  def filterByStage(stage: Stage): HostList = new HostList(hosts.filter(_.stage == stage.name))

  def byStackAndApp = {
    implicit val appOrder: Ordering[App] = Ordering.by(_.name)
    implicit val hostOrder: Ordering[Host] = Ordering.by(_.name)
    implicit def someBeforeNone[T](implicit ord: Ordering[T]): Ordering[Option[T]] =
      new OptionOrdering[T] { val optionOrdering = ord.reverse }.reverse
    implicit def setOrder[T](implicit ord: Ordering[T]): Ordering[Set[T]] = Ordering.by(_.toIterable)
    implicit def seqOrder[T](implicit ord: Ordering[T]): Ordering[Seq[T]] = Ordering.by(_.toIterable)

    hosts.groupBy(h => (h.stack, h.apps)).toSeq.sorted
  }
}
object HostList {
  implicit def listOfHostsAsHostList(hosts: Seq[Host]): HostList = new HostList(hosts)
  implicit def hostListAsListOfHosts(hostList: HostList) = hostList.hosts
}

case class DeploymentResources(reporter: DeployReporter, lookup: Lookup) {
  def assembleKeyring(target: DeployTarget, pkg: DeploymentPackage): KeyRing =
    lookup.keyRing(target.parameters.stage, pkg.apps.toSet, target.stack)
}

case class DeployTarget(parameters: DeployParameters, stack: Stack)

/*
 An action represents a step within a recipe. It isn't executable
 until it's resolved against a particular host.
 */
trait Action {
  def apps: Seq[App]
  def description: String
  def resolve(resources: DeploymentResources, target: DeployTarget): List[Task]
}

case class App (name: String)

case class Recipe(
  name: String,
  actionsBeforeApp: Iterable[Action] = Nil, //executed once per app (before the host actions are executed)
  actionsPerHost: Iterable[Action] = Nil,  //executed once per host in the application
  dependsOn: List[String] = Nil
)

case class Project(
  packages: Map[String, DeploymentPackage] = Map.empty,
  recipes: Map[String, Recipe] = Map.empty,
  defaultStacks: Seq[Stack] = Seq()
) {
  lazy val applications = packages.values.flatMap(_.apps).toSet
}

case class Stage(name: String)
case class Build(projectName:String, id:String)
case class RecipeName(name:String)
object DefaultRecipe {
  def apply() = RecipeName("default")
}

sealed trait Stack {
  def nameOption: Option[String]
}
case class NamedStack(name: String) extends Stack {
  override def nameOption = Some(name)
}
case object UnnamedStack extends Stack {
  override def nameOption = None
}

case class Deployer(name: String)

case class DeployParameters(
                             deployer: Deployer,
                             build: Build,
                             stage: Stage,
                             recipe: RecipeName = DefaultRecipe(),
                             stacks: Seq[NamedStack] = Seq(),
                             hostList: List[String] = Nil
                             ) {

  def toDeployContext(uuid: UUID, project: Project, resourceLookup: Lookup, reporter: DeployReporter): DeployContext = {
    DeployContext(uuid, this, project, resourceLookup, reporter)
  }

  def matchingHost(hostName:String) = hostList.isEmpty || hostList.contains(hostName)
}
