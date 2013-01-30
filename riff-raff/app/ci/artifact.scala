package ci

import xml.{NodeSeq, Node, Elem}
import utils.ScheduledAgent
import akka.util.duration._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import controllers.Logging
import scala.Predef._
import collection.mutable
import magenta.DeployParameters
import play.api.libs.concurrent.Promise

object `package` {
  type BuildTypeMap = Map[BuildType,List[Build]]

  implicit def buildTypeBuildsMap2helpers(buildTypeMap: BuildTypeMap) = new {
    def latestBuildId(): Int = buildTypeMap.values.flatMap(_.map(_.buildId)).max
    def filterBuilds(buildTypeFilter: BuildType => Boolean, buildFinder: List[Build] => List[Build]): Map[BuildType,List[Build]] = {
      val filteredBuildTypes = buildTypeMap.filterKeys(buildTypeFilter)
      filteredBuildTypes.flatMap { case (buildType, builds) =>
        val newBuildsForType = buildFinder(builds)
        if (newBuildsForType.isEmpty)
          None
        else
          Some(buildType -> newBuildsForType)
      }
    }
  }

  implicit def promiseIterable2FlattenMap[A](promiseIterable: Promise[Iterable[A]]) = new {
    def flatPromiseMap[B](p: A => Promise[Iterable[B]]):Promise[Iterable[B]] = {
      promiseIterable.flatMap { iterable =>
        val newPromises:Iterable[Promise[Iterable[B]]] = iterable.map(p)
        Promise.sequence(newPromises).map(_.flatten)
      }
    }
  }
}

object ContinuousIntegration {
  def getMetaData(projectName: String, buildId: String): Map[String, String] = {
    val projectNameBuilds = TeamCity.buildMap.find(_._1.name == projectName).map(_._2)
    val build = projectNameBuilds.flatMap(builds => builds.find(_.number == buildId))
    build.map { build => Map(
      "branch" -> build.branch
    )}.getOrElse(Map.empty)
  }
}

trait BuildWatcher {
  def change(previous: BuildTypeMap, current: BuildTypeMap)
}

case class BuildType(id: String, name: String)

object Build {
  val dateTimeParser = DateTimeFormat.forPattern("yyyyMMdd'T'HHmmssZ")

  def apply(buildId: Int, name: String, number: String, startDate: String, branch: String): Build = {
    val parsedStartDate: DateTime = dateTimeParser.parseDateTime(startDate)
    apply(buildId: Int, name, number, parsedStartDate, branch)
  }

  def apply(build: Node): Build = {
    apply((build \ "@id" text).toInt, build \ "@buildTypeId" text, build \ "@number" text, build \ "@startDate" text, (build \ "@branchName").headOption.map(_.text).getOrElse("default"))
  }

  def apply(buildElements: Elem): List[Build] = {
    (buildElements \ "build").toList filter {
      build => (build \ "@status").text == "SUCCESS"
    } map { apply(_) }
  }
}
case class Build(buildId: Int, name: String, number: String, startDate: DateTime, branch: String)

object TeamCity extends Logging {
  private val listeners = mutable.Buffer[BuildWatcher]()
  def subscribe(sink: BuildWatcher) { listeners += sink }
  def unsubscribe(sink: BuildWatcher) { listeners -= sink }

  object api {
    val projectList = TeamCityWS.url("/app/rest/projects")
    def project(project: String) = TeamCityWS.url("/app/rest/projects/id:%s" format project)
    def buildList(buildTypeId: String) = TeamCityWS.url("/app/rest/builds/?locator=buildType:%s,branch:branched:any" format buildTypeId)
    def buildSince(buildId:Int) = TeamCityWS.url("/app/rest/builds/?locator=sinceBuild:%d,branch:branched:any" format buildId)
  }

  private val buildAgent = ScheduledAgent[BuildTypeMap](0 seconds, 1 minute, Map.empty[BuildType,List[Build]]){ currentBuildMap =>
    if (currentBuildMap.isEmpty || !getBuildsSince(currentBuildMap.latestBuildId()).await.get.isEmpty) {
      val buildTypes = getRetrieveBuildTypes
      buildTypes.flatMap { fulfilledBuildTypes =>
        log.debug("Found %d buildTypes" format fulfilledBuildTypes.size)
        getSuccessfulBuildMap(fulfilledBuildTypes).map { result =>
          log.info("Finished updating TC information (found %d buildTypes and %d successful builds)" format(result.size, result.values.map(_.size).fold(0)(_+_)))
          listeners.foreach{ listener =>
            try listener.change(currentBuildMap,result)
            catch {
              case e:Exception => log.error("Listener threw an exception", e)
            }
          }
          result
        }
      }
    }.await((1 minute).toMillis).get else currentBuildMap
  }

  def buildMap = buildAgent()
  def buildTypes = buildMap.keys.toList

  def successfulBuilds(projectName: String): List[Build] = buildTypes.filter(_.name == projectName).headOption
    .flatMap(buildMap.get(_)).getOrElse(Nil)

  def transformLastSuccessful(params: DeployParameters): DeployParameters = {
    if (params.build.id != "lastSuccessful")
      params
    else {
      val builds = successfulBuilds(params.build.projectName)
      builds.headOption.map{ latestBuild =>
        params.copy(build = params.build.copy(id = latestBuild.number))
      }.getOrElse(params)
    }
  }

  private def getRetrieveBuildTypes: Promise[List[BuildType]] = {
    val ws = api.projectList
    log.debug("Querying TC for build types: %s" format ws.url)
    val projectElement:Promise[NodeSeq] = ws.get().map(_.xml).map(_ \ "project")
    val buildTypes = projectElement.flatPromiseMap(node => getProjectBuildTypes((node \ "@id").text))
    buildTypes.map(_.toList)
  }

  private def getProjectBuildTypes(project: String): Promise[List[BuildType]] = {
    val btWs = api.project(project)
    log.debug("Getting: %s" format btWs.url)
    btWs.get().map(_.xml).map{ buildTypeElements =>
      val buildTypes = (buildTypeElements \ "buildTypes" \ "buildType").map { buildType =>
        log.debug("found %s" format (buildType \ "@id"))
        BuildType(buildType \ "@id" text, "%s::%s" format(buildType \ "@projectName" text, buildType \ "@name" text))
      }
      buildTypes.toList
    }
  }

  private def getSuccessfulBuildMap(buildTypes: List[BuildType]): Promise[BuildTypeMap] = {
    log.info("Querying TC for all successful builds")
    Promise.sequence(buildTypes.map(getSuccessfulBuilds)).map(_.toMap)
  }

  private def getSuccessfulBuilds(buildType: BuildType): Promise[(BuildType,List[Build])] = {
    val ws = api.buildList(buildType.id)
    log.debug("Getting %s" format ws.url)
    ws.get().map(_.xml).map { buildType -> Build(_) }
  }

  private def getBuildsSince(buildId:Int): Promise[List[Build]] = {
    log.info("Querying TC for all builds since %d" format buildId)
    val ws = api.buildSince(buildId)
    log.debug("Getting %s" format ws.url)
    ws.get().map(_.xml).map { buildElements =>
      val builds = Build(buildElements)
      log.info("Found %d builds since %d" format (builds.size, buildId))
      log.info("Discovered builds: \n%s" format builds.mkString("\n"))
      builds
    }
  }
}

