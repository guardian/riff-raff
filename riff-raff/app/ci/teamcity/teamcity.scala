package ci.teamcity

import java.net.{URL, URLEncoder}

import ci.CIBuild
import ci.teamcity.TeamCity.{BuildLocator, api}
import conf.Configuration.teamcity
import controllers.Logging
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import play.api.libs.ws.{WSRequestHolder, _}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.{Elem, Node, NodeSeq}

case class Project(id: String, name: String)
object Project extends Logging {
  def apply(): Future[List[Project]] = {
    val ws = api.project.list
    log.debug("Getting list of projects from %s" format ws.url)
    ws.get().map{ data => Project(data.xml) }
  }
  def apply(xml: Elem): List[Project] = {
    (xml \ "project").toList map { projectNode => Project((projectNode \ "@id").text, (projectNode \ "@name").text) }
  }
}

trait Job {
  def id: String
  def name: String
}

case class BuildType(id: String, typeName: String, project: Project) extends Job {
  lazy val name = "%s::%s" format (project.name, typeName)
}
object BuildType extends Logging {
  def apply(xml: NodeSeq): List[BuildType] = {
    val nodes = xml.toList
    log.debug("Processing %d build types" format nodes.size)
    nodes map { BuildType(_) }
  }
  def apply(buildType: Node): BuildType = {
    BuildType(
      buildType \ "@id" text,
      buildType \ "@name" text,
      Project(buildType \ "@projectId" text, buildType \ "@projectName" text)
    )
  }
}

trait TeamcityBuild extends CIBuild with Logging {
  def status: String
  def buildTypeId: String
  def startDate: DateTime
  def job: Job
  def detail: Future[BuildDetail]
  def jobName = job.name
  def startTime = startDate

  def jobId = job.id
}

case class BuildSummary(id: Long,
                        number: String,
                        buildTypeId: String,
                        status: String,
                        branchName: String,
                        startDate: DateTime,
                        job: Job
                         ) extends TeamcityBuild {
  def detail: Future[BuildDetail] = BuildDetail(BuildLocator(id=Some(id)))
}
object BuildSummary extends Logging {
  def wrapLookup(original: String => Option[Job]) = (id: String) => Future.successful(original(id))

  def apply(locator: BuildLocator, job: Job, followNext: Boolean = false): Future[List[BuildSummary]] = {
    listWithLookup(locator, (id: String) => { Some(job) }, followNext)
  }

  def listWithLookup(locator: BuildLocator, jobLookup: String => Option[Job], followNext:Boolean = false): Future[List[BuildSummary]] = {
    listWithFuturedLookup(locator, wrapLookup(jobLookup), followNext)
  }

  def listWithFuturedLookup(locator: BuildLocator, jobLookup: String => Future[Option[Job]], followNext:Boolean = false): Future[List[BuildSummary]] = {
    log.debug("Getting build summaries for %s" format locator)
    api.build.list(locator).get().flatMap( data => BuildSummary(data.xml, jobLookup, followNext) )
  }

  private def apply(build: Node, jobLookup: String => Future[Option[Job]]): Future[Option[BuildSummary]] = {
    val buildTypeId = build \ "@buildTypeId" text
    val buildSummary = jobLookup(buildTypeId).map { job =>
      if (job.isEmpty) log.warn("No build type found for %s" format buildTypeId)
      job.map { bt =>
        apply(
          (build \ "@id" text).toInt,
          build \ "@number" text,
          build \ "@buildTypeId" text,
          build \ "@status" text,
          (build \ "@branchName").headOption.map(_.text).getOrElse("default"),
          TeamCity.dateTimeFormat.parseDateTime(build \ "startDate" text),
          bt
        )
      }
    }
    buildSummary
  }

  val BrokenSinceDateMatcher = """^(.*sinceDate:\d{8}T\d{6})\+(\d{4}.*)$""".r

  def apply(builds: Elem, jobLookup: String => Future[Option[Job]], followNext: Boolean): Future[List[BuildSummary]] = {
    val buildSummaries = Future.sequence((builds \ "build").toList map( apply(_, jobLookup) )).map(_.flatten)
    (builds \ "@nextHref").headOption match {
      case Some(continuationUrl) if followNext =>
        val fixedContinuationUrl = continuationUrl.text match {
          case BrokenSinceDateMatcher(start,end) => s"$start%2B$end"
          case other => other
        }
        val continuations = api.href(fixedContinuationUrl).get().flatMap{ data =>
          if (data.status < 400)
            BuildSummary(data.xml, jobLookup, followNext)
          else {
            log.warn(s"Status ${data.status} when trying to get further results from $fixedContinuationUrl")
            Future.successful(Nil)
          }
        }
        Future.sequence(List(buildSummaries, continuations)).map(_.flatten)
      case _ => buildSummaries
    }
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

case class VCSRootInstance(lastVersion: String, id: Int, name: String, vcsName: String, properties: Map[String, String])
object VCSRootInstance {
  def apply(xml: Node): VCSRootInstance = {
    VCSRootInstance(
      lastVersion = xml \ "@lastVersion" text,
      id = (xml \ "@id" text).toInt,
      name = xml \ "@name" text,
      vcsName = xml \ "@vcsName" text,
      properties = (xml \ "properties" \ "property").toList.map { property =>
        (property \ "@name" text) -> (property \ "@value" text)
      }.toMap
    )
  }
}

case class Revision(version: String, vcsInstanceId: Int, vcsRootId: String, vcsName: String, vcsHref: String) {
  def vcsDetails = TeamCityWS.href(vcsHref).get().map(data => VCSRootInstance(data.xml))
}
object Revision {
  def apply(xml: Node): Revision = {
    Revision(
      xml \ "@version" text,
      (xml \ "vcs-root-instance" \ "@id" text).toInt,
      (xml \ "vcs-root-instance" \ "@vcs-root-id" text),
      xml \ "vcs-root-instance" \ "@name" text,
      xml \ "vcs-root-instance" \ "@href" text
    )
  }
}

case class BuildDetail(
  id: Long,
  number: String,
  job: BuildType,
  status: String,
  webUrl: URL,
  branchName: String,
  defaultBranch: Option[Boolean],
  startDate: DateTime,
  finishDate: DateTime,
  pinInfo: Option[PinInfo],
  revision: Option[Revision]
) extends TeamcityBuild {
  def detail = Future.successful(this)
  def buildTypeId = job.id
}
object BuildDetail {
  def apply(locator: BuildLocator): Future[BuildDetail] = {
    api.build.detail(locator).get().map( data => BuildDetail(data.xml) )
  }
  def apply(build: Elem): BuildDetail = {
    BuildDetail(
      id = (build \ "@id" text).toInt,
      number = build \ "@number" text,
      job = BuildType(build \ "buildType" head),
      status = build \ "@status" text,
      webUrl = new URL(build \ "@webUrl" text),
      branchName = (build \ "@branchName").headOption.map(_.text).getOrElse("default"),
      defaultBranch = (build \ "@defaultBranch").headOption.map(_.text == "true"),
      startDate = TeamCity.dateTimeFormat.parseDateTime(build \ "startDate" text),
      finishDate = TeamCity.dateTimeFormat.parseDateTime(build \ "finishDate" text),
      pinInfo = (build \ "pinInfo" headOption) map (PinInfo(_)),
      revision = (build \ "revisions" \ "revision" headOption) map (Revision(_))
    )
  }
}

object TeamCity {
  def encode(toEncode:String) = URLEncoder.encode(toEncode,"UTF-8")

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
                           id: Option[Long] = None,
                           branch: Option[BranchLocator] = Some(BranchLocator(branched = Some("any"))),
                           buildType: Option[BuildTypeLocator] = None,
                           buildTypeInstance: Option[BuildType] = None,
                           sinceBuild: Option[Int] = None,
                           sinceDate: Option[DateTime] = None,
                           number: Option[String] = None,
                           pinned: Option[Boolean] = None,
                           status: Option[String] = None
                           ) extends Locator {
    lazy val params = Map(
      "branch" -> branch.map(_.toString),
      "buildType" -> buildType.map(_.toString),
      "sinceBuild" -> sinceBuild.map(_.toString),
      "sinceDate" -> sinceDate.map(date => URLEncoder.encode(TeamCity.dateTimeFormat.print(date), "UTF-8")),
      "number" -> number.map(encode),
      "pinned" -> pinned.map(_.toString),
      "status" -> status.map(encode),
      "id" -> id.map(_.toString)
    )
    def list: Future[List[BuildSummary]] = {
      if (buildTypeInstance.isDefined)
        BuildSummary(this, buildTypeInstance.get)
      else {
        val buildTypes = BuildTypeLocator.list
        val lookupFromTC = (id: String) => {
          buildTypes.map(_.find(_.id == id))
        }
        BuildSummary.listWithFuturedLookup(this, lookupFromTC)
      }
    }
    def detail: Future[BuildDetail] = BuildDetail(this)
    def number(number:String): BuildLocator = copy(number=Some(number))
    def status(status:String): BuildLocator = copy(status=Some(status))
    def sinceBuild(buildId: Int): BuildLocator = copy(sinceBuild=Some(buildId))
    def sinceDate(date: DateTime):BuildLocator = copy(sinceDate=Some(date))
    def buildTypeId(buildTypeId: String) = copy(buildType=Some(BuildTypeLocator.id(buildTypeId)))
    def buildTypeInstance(buildType: BuildType) = copy(buildType=Some(BuildTypeLocator.id(buildType.id)), buildTypeInstance=Some(buildType))
  }
  object BuildLocator {
    def id(id: Long) = BuildLocator(id=Some(id))
    def buildTypeId(buildTypeId: String) = BuildLocator(buildType=Some(BuildTypeLocator.id(buildTypeId)))
    def buildTypeInstance(buildType: BuildType) = BuildLocator(buildType=Some(BuildTypeLocator.id(buildType.id)), buildTypeInstance=Some(buildType))
    def sinceBuild(buildId: Int) = BuildLocator(sinceBuild=Some(buildId))
    def sinceDate(date: DateTime) = BuildLocator(sinceDate=Some(date))
    def number(buildNumber: String) = BuildLocator(number=Some(buildNumber))
    def pinned(pinned: Boolean) = BuildLocator(pinned=Some(pinned))
    def status(status: String) = BuildLocator(status=Some(status))
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
    def list: Future[List[Job]] = api.buildType.list.get().map(data => BuildType(data.xml \ "buildType"))
  }
  case class ProjectLocator(id: Option[String] = None, name: Option[String] = None) extends Locator {
    lazy val params = Map(
      "id" -> id,
      "name" -> name
    )
  }
  object ProjectLocator {
    def id(projectId: String) = ProjectLocator(id=Some(projectId))
    def name(projectName: String) = ProjectLocator(name=Some(projectName))
    def apply(project: Project): ProjectLocator = ProjectLocator(id=Some(project.id))
    def list: Future[List[Project]] = Project()
  }

  object api {
    object project {
      def list = TeamCityWS.url("/app/rest/projects")
      def detail(projectLocator: ProjectLocator) = TeamCityWS.url("/app/rest/projects/%s" format projectLocator)
      def id(projectId: String) = detail(ProjectLocator(id=Some(projectId)))
    }

    object buildType {
      def list = TeamCityWS.url("/app/rest/buildTypes")
    }

    object build {
      def detail(buildLocator: BuildLocator) = TeamCityWS.url("/app/rest/builds/%s" format buildLocator)
      def list(buildLocator: BuildLocator) = TeamCityWS.url(s"/app/rest/builds/?locator=$buildLocator&fields=build(id,number,status,startDate,branchName,buildTypeId,webUrl)")
      def buildType(buildTypeId: String) = list(BuildLocator.buildTypeId(buildTypeId))
      def since(buildId:Int) = list(BuildLocator.sinceBuild(buildId))
      def since(startTime:DateTime) = list(BuildLocator.sinceDate(startTime))

      def pin(buildLocator: BuildLocator): WSRequestHolder = TeamCityWS.url("/app/rest/builds/%s/pin" format buildLocator)
      def pin(buildTypeId: String, buildNumber: String): WSRequestHolder = pin(BuildLocator.buildTypeId(buildTypeId).number(buildNumber))
    }

    def href(href: String) = TeamCityWS.href(href)
  }
}


object TeamCityWS extends Logging {
  import play.api.Play.current

  case class Auth(user:String, password:String, scheme:WSAuthScheme=WSAuthScheme.BASIC)

  val auth = if (teamcity.useAuth) Some(Auth(teamcity.user.get, teamcity.password.get)) else None
  val teamcityURL =teamcity.serverURL.map(url => "%s/%s" format (url, if (auth.isDefined) "httpAuth" else "guestAuth"))

  def url(path: String): WSRequestHolder = {
    assert(teamcityURL.isDefined, "TeamCity is not configured")
    val url = "%s%s" format (teamcityURL.get, path)
    log.debug(s"Fetching: $url")
    auth.map(ui => WS.url(url).withAuth(ui.user, ui.password, ui.scheme)).getOrElse(WS.url(url))
  }

  def href(href: String): WSRequestHolder = {
    val Stripper = """/[a-z]*Auth(.*)""".r
    val Stripper(apiUrl) = href
    url(apiUrl)
  }
}
