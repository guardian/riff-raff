package ci

import xml.{NodeSeq, Node, Elem}
import utils.ScheduledAgent
import akka.util.duration._
import org.joda.time.{Duration, DateTime}
import org.joda.time.format.DateTimeFormat
import controllers.Logging
import scala.Predef._
import collection.mutable
import magenta.DeployParameters
import play.api.libs.concurrent.Promise
import java.net.URLEncoder

object `package` {
  implicit def listOfBuild2helpers(builds: List[Build]) = new {
    def buildTypes: Set[BuildType] = builds.map(_.buildType).toSet
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
    val build = TeamCity.builds.find(build => build.buildType.name == projectName && build.number == buildId)
    build.map { build => Map(
      "branch" -> build.branch
    )}.getOrElse(Map.empty)
  }
}

trait BuildWatcher {
  def newBuilds(builds: List[Build])
}

case class BuildType(id: String, name: String)

object Build {
  def apply(buildId: Int, buildType: BuildType, number: String, startDate: String, branch: String): Build = {
    val parsedStartDate: DateTime = TeamCity.dateTimeFormat.parseDateTime(startDate)
    apply(buildId: Int, buildType, number, parsedStartDate, branch)
  }

  private def apply(buildTypes: String => BuildType, build: Node): Build = {
    apply((build \ "@id" text).toInt, buildTypes(build \ "@buildTypeId" text), build \ "@number" text, build \ "@startDate" text, (build \ "@branchName").headOption.map(_.text).getOrElse("default"))
  }

  def apply(buildTypes: String => BuildType, buildElements: Elem): List[Build] = {
    (buildElements \ "build").toList filter {
      build => (build \ "@status").text == "SUCCESS"
    } map { apply(buildTypes, _) }
  }
}
case class Build(buildId: Int, buildType: BuildType, number: String, startDate: DateTime, branch: String)

object TeamCity extends Logging {
  val dateTimeFormat = DateTimeFormat.forPattern("yyyyMMdd'T'HHmmssZ")
  val pollingWindow = Duration.standardMinutes(conf.Configuration.teamcity.pollingWindowMinutes)
  val pollingPeriod = conf.Configuration.teamcity.pollingPeriodSeconds.seconds

  private val listeners = mutable.Buffer[BuildWatcher]()
  def subscribe(sink: BuildWatcher) { listeners += sink }
  def unsubscribe(sink: BuildWatcher) { listeners -= sink }

  object api {
    val projectList = TeamCityWS.url("/app/rest/projects")
    def project(project: String) = TeamCityWS.url("/app/rest/projects/id:%s" format project)
    def buildList(buildTypeId: String) = TeamCityWS.url("/app/rest/builds/?locator=buildType:%s,branch:branched:any" format buildTypeId)
    def buildSince(buildId:Int) = TeamCityWS.url("/app/rest/builds/?locator=sinceBuild:%d,branch:branched:any" format buildId)
    def buildSince(startTime:DateTime) = TeamCityWS.url("/app/rest/builds/?locator=sinceDate:%s,branch:branched:any" format URLEncoder.encode(dateTimeFormat.print(startTime), "UTF-8"))
  }

  private val buildAgent = ScheduledAgent[List[Build]](0 seconds, pollingPeriod, List.empty[Build]){ currentBuilds =>
    val buildMapPromise = if (currentBuilds.isEmpty)
      getSuccessfulBuilds
    else {
      getNewBuilds(currentBuilds).map { newBuilds =>
        if (newBuilds.isEmpty)
          currentBuilds
        else {
          listeners.foreach{ listener =>
            try listener.newBuilds(newBuilds)
            catch {
              case e:Exception => log.error("BuildWatcher threw an exception", e)
            }
          }
          (currentBuilds ++ newBuilds).sortBy(-_.buildId)
        }
      }
    }

    buildMapPromise.await((1 minute).toMillis).get
  }

  def builds: List[Build] = buildAgent()
  def buildTypes = builds.buildTypes

  def successfulBuilds(projectName: String): List[Build] = builds.filter(_.buildType.name == projectName)

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

  private def getBuildTypes: Promise[List[BuildType]] = {
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

  private def getSuccessfulBuilds: Promise[List[Build]] = {
    val buildTypes = getBuildTypes
    buildTypes.flatMap { fulfilledBuildTypes =>
      log.debug("Found %d buildTypes" format fulfilledBuildTypes.size)
      getSuccessfulBuilds(fulfilledBuildTypes).map { result =>
        log.info("Finished updating TC information (found %d buildTypes and %d successful builds)" format(fulfilledBuildTypes.size, result.size))
        result
      }
    }
  }

  private def getSuccessfulBuilds(buildTypes: List[BuildType]): Promise[List[Build]] = {
    log.info("Querying TC for all successful builds")
    Promise.sequence(buildTypes.map(bt => getSuccessfulBuilds(bt))).map(_.flatten)
  }

  private def getSuccessfulBuilds(buildType: BuildType): Promise[List[Build]] = {
    val ws = api.buildList(buildType.id)
    log.debug("Getting %s" format ws.url)
    ws.get().map(_.xml).map { Build(Map(buildType.id -> buildType), _) }
  }

  private def getNewBuilds(currentBuilds: List[Build]): Promise[List[Build]] = {
    val knownBuilds = currentBuilds.map(_.buildId).toSet
    val pollingWindowStart = (new DateTime()).minus(pollingWindow)
    log.info("Querying TC for all builds since %s" format pollingWindowStart)
    val ws = api.buildSince(pollingWindowStart)
    log.debug("Getting %s" format ws.url)
    ws.get().map(_.xml).map { buildElements =>
      val builds = Build(currentBuilds.map(b => b.buildType.id -> b.buildType).toMap, buildElements)
      log.info("Found %d builds since %s" format (builds.size, pollingWindowStart))
      val newBuilds = builds.filterNot(build => knownBuilds.contains(build.buildId))
      log.info("Discovered builds: \n%s" format newBuilds.mkString("\n"))
      newBuilds
    }
  }
}

