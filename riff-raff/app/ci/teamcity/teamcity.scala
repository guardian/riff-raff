package ci.teamcity

import play.api.libs.ws.WS
import com.ning.http.client.Realm.AuthScheme
import conf.Configuration.teamcity
import play.api.libs.ws.WS.WSRequestHolder
import org.joda.time.format.DateTimeFormat
import org.joda.time.DateTime
import java.net.{URL, URLEncoder}
import play.api.libs.concurrent.Promise
import xml.{NodeSeq, Node, Elem}
import ci.teamcity.TeamCity.{api, BuildLocator, ProjectLocator}
import controllers.Logging

case class Project(id: String, name: String) {
  def buildTypes: Promise[List[BuildType]] = BuildType(this)
}
object Project extends Logging {
  def apply(): Promise[List[Project]] = {
    val ws = api.project.list
    log.debug("Getting list of projects from %s" format ws.url)
    ws.get().map{ data => Project(data.xml) }
  }
  def apply(xml: Elem): List[Project] = {
    (xml \ "project").toList map { projectNode => Project((projectNode \ "@id").text, (projectNode \ "@name").text) }
  }
}

case class BuildType(id: String, name: String, project: Project, webUrl: URL) {
  def builds = BuildSummary(BuildLocator.buildTypeId(id), Some(this))
  lazy val fullName = "%s::%s" format (project.name, name)
}
object BuildType extends Logging {
  def apply(project: Project): Promise[List[BuildType]] = {
    val ws = api.project.detail(ProjectLocator(project))
    log.debug("Getting list of buildTypes from %s" format ws.url)
    ws.get().map( data => BuildType(data.xml \ "buildTypes" \ "buildType"))
  }
  def apply(xml: NodeSeq): List[BuildType] = {
    val nodes = xml.toList
    log.debug("Processing %d build types" format nodes.size)
    nodes map { BuildType(_) }
  }
  def apply(buildType: Node): BuildType = {
    BuildType(
      buildType \ "@id" text,
      buildType \ "@name" text,
      Project(buildType \ "@projectId" text, buildType \ "@projectName" text),
      new URL(buildType \ "@webUrl" text)
    )
  }
}

trait Build {
  def id: Int
  def number: String
  def status: String
  def webUrl: URL
  def buildTypeId: String
  def startDate: DateTime
  def buildType: BuildType
  def branchName: String
  def defaultBranch: Option[Boolean]
  def detail: Promise[BuildDetail]
}

case class BuildSummary(id: Int,
                        number: String,
                        buildTypeId: String,
                        status: String,
                        webUrl: URL,
                        branchName: String,
                        defaultBranch: Option[Boolean],
                        startDate: DateTime,
                        buildType: BuildType
                         ) extends Build {
  def detail: Promise[BuildDetail] = BuildDetail(BuildLocator(id=Some(id)))
}
object BuildSummary extends Logging {
  def apply(locator: BuildLocator, buildType: Option[BuildType] = None): Promise[List[BuildSummary]] = {
    def lookup = (id: String) => { buildType }
    api.build.list(locator).get().map( data => BuildSummary(data.xml, lookup) )
  }

  def listWithLookup(locator: BuildLocator, buildTypeLookup: String => Option[BuildType]): Promise[List[BuildSummary]] = {
    log.debug("Getting build summaries for %s" format locator)
    api.build.list(locator).get().map( data => BuildSummary(data.xml, buildTypeLookup) )
  }

  private def apply(build: Node, buildTypeLookup: String => Option[BuildType]): Option[BuildSummary] = {
    val buildTypeId = build \ "@buildTypeId" text
    val buildType = buildTypeLookup(buildTypeId)
    if (buildType.isEmpty) log.warn("No build type found for %s" format buildTypeId)
    buildType.map { bt =>
      apply(
        (build \ "@id" text).toInt,
        build \ "@number" text,
        build \ "@buildTypeId" text,
        build \ "@status" text,
        new URL(build \ "@webUrl" text),
        (build \ "@branchName").headOption.map(_.text).getOrElse("default"),
        (build \ "@defaultBranch").headOption.map(_.text == "true"),
        TeamCity.dateTimeFormat.parseDateTime(build \ "@startDate" text),
        bt
      )
    }
  }

  def apply(builds: Elem, buildTypeLookup: String => Option[BuildType]): List[BuildSummary] = {
    (builds \ "build").toList flatMap { apply(_, buildTypeLookup) }
  }
}

case class User(username: String, id: Int, name: String)
object User {
  def apply(xml: Node): User = {
    User(xml \ "@username" text, (xml \ "@id" text).toInt, xml \ "@name" text)
  }
}

case class PinInfo(user: User, timestamp: DateTime, text: String)
object PinInfo {
  def apply(xml: Node): PinInfo = {
    PinInfo(
      User(xml \ "user" head),
      TeamCity.dateTimeFormat.parseDateTime(xml \ "timestamp" text),
      xml \ "text" text
    )
  }
}

case class BuildDetail(
  id: Int,
  number: String,
  buildType: BuildType,
  status: String,
  webUrl: URL,
  branchName: String,
  defaultBranch: Option[Boolean],
  startDate: DateTime,
  finishDate: DateTime,
  pin: Option[PinInfo]
) extends Build {
  def detail = Promise.pure(this)
  def buildTypeId = buildType.id
}
object BuildDetail {
  def apply(locator: BuildLocator): Promise[BuildDetail] = {
    api.build.detail(locator).get().map( data => BuildDetail(data.xml) )
  }
  def apply(build: Elem): BuildDetail = {
    BuildDetail(
      id = (build \ "@id" text).toInt,
      number = build \ "@number" text,
      buildType = BuildType(build \ "buildType" head),
      status = build \ "@status" text,
      webUrl = new URL(build \ "@webUrl" text),
      branchName = (build \ "@branchName").headOption.map(_.text).getOrElse("default"),
      defaultBranch = (build \ "@defaultBranch").headOption.map(_.text == "true"),
      startDate = TeamCity.dateTimeFormat.parseDateTime(build \ "startDate" text),
      finishDate = TeamCity.dateTimeFormat.parseDateTime(build \ "finishDate" text),
      pin = (build \ "pinInfo" headOption) map (PinInfo(_))
    )
  }
}

object TeamCity {
  val dateTimeFormat = DateTimeFormat.forPattern("yyyyMMdd'T'HHmmssZ")

  trait Locator {
    def params: Map[String, Option[String]]
    override def toString: String = {
      params.flatMap{ case (key, value) =>
        value.map(v => "%s:%s" format (key, v))
      }.mkString(",")
    }
  }

  case class BuildLocator(
                           id: Option[Int] = None,
                           branch: Option[BranchLocator] = Some(BranchLocator(branched = Some("any"))),
                           buildType: Option[BuildTypeLocator] = None,
                           sinceBuild: Option[Int] = None,
                           sinceDate: Option[DateTime] = None,
                           number: Option[String] = None
                           ) extends Locator {
    lazy val params = Map(
      "branch" -> branch.map(_.toString),
      "buildType" -> buildType.map(_.toString),
      "sinceBuild" -> sinceBuild.map(_.toString),
      "sinceDate" -> sinceDate.map(date => URLEncoder.encode(TeamCity.dateTimeFormat.print(date), "UTF-8")),
      "number" -> number
    )
    def list: Promise[List[BuildSummary]] = BuildSummary(this)
    def detail: Promise[BuildDetail] = BuildDetail(this)
    def number(number:String) = copy(number=Some(number))
  }
  object BuildLocator {
    def buildTypeId(buildTypeId: String) = BuildLocator(buildType=Some(BuildTypeLocator.id(buildTypeId)))
    def sinceBuild(buildId: Int) = BuildLocator(sinceBuild=Some(buildId))
    def sinceDate(date: DateTime) = BuildLocator(sinceDate=Some(date))
    def number(buildNumber: String) = BuildLocator(number=Some(buildNumber))
  }

  case class BranchLocator(branched: Option[String] = None) extends Locator {
    lazy val params = Map(
      "branched" -> branched
    )
  }
  case class BuildTypeLocator(id: Option[String] = None, name: Option[String] = None) extends Locator {
    lazy val params = Map(
      "id" -> id,
      "name" -> name
    )
  }
  object BuildTypeLocator {
    def id(buildTypeId: String) = BuildTypeLocator(id=Some(buildTypeId))
    def name(buildTypeName: String) = BuildTypeLocator(name=Some(buildTypeName))
  }
  case class ProjectLocator(id: Option[String] = None, name: Option[String] = None) extends Locator {
    lazy val params = Map(
      "id" -> id,
      "name" -> name
    )
    def buildTypes: WSRequestHolder = api.project.detail(this)
  }
  object ProjectLocator {
    def id(projectId: String) = ProjectLocator(id=Some(projectId))
    def name(projectName: String) = ProjectLocator(name=Some(projectName))
    def apply(project: Project): ProjectLocator = ProjectLocator(id=Some(project.id))
    def list: Promise[List[Project]] = Project()
  }

  object api {
    object project {
      def list = TeamCityWS.url("/app/rest/projects")
      def detail(projectLocator: ProjectLocator) = TeamCityWS.url("/app/rest/projects/%s" format projectLocator)
      def id(projectId: String) = detail(ProjectLocator(id=Some(projectId)))
    }

    object build {
      def detail(buildLocator: BuildLocator) = TeamCityWS.url("/app/rest/builds/%s" format buildLocator)
      def list(buildLocator: BuildLocator) = TeamCityWS.url("/app/rest/builds/?locator=%s" format buildLocator)
      def buildType(buildTypeId: String) = list(BuildLocator.buildTypeId(buildTypeId))
      def since(buildId:Int) = list(BuildLocator.sinceBuild(buildId))
      def since(startTime:DateTime) = list(BuildLocator.sinceDate(startTime))

      def pin(buildLocator: BuildLocator): WSRequestHolder = TeamCityWS.url("/app/rest/builds/%s/pin" format buildLocator)
      def pin(buildTypeId: String, buildNumber: String): WSRequestHolder = pin(BuildLocator.buildTypeId(buildTypeId).number(buildNumber))
    }
  }
}


object TeamCityWS {
  case class Auth(user:String, password:String, scheme:AuthScheme=AuthScheme.BASIC)

  val auth = if (teamcity.useAuth) Some(Auth(teamcity.user.get, teamcity.password.get)) else None
  val teamcityURL ="%s/%s" format (teamcity.serverURL, if (auth.isDefined) "httpAuth" else "guestAuth")

  def url(path: String): WSRequestHolder = {
    val url = "%s%s" format (teamcityURL, path)
    auth.map(ui => WS.url(url).withAuth(ui.user, ui.password, ui.scheme)).getOrElse(WS.url(url))
  }
}
