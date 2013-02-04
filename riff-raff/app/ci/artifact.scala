package ci

import xml.{NodeSeq, Node, Elem}
import utils.{PeriodicScheduledAgentUpdate, ScheduledAgent}
import akka.util.duration._
import org.joda.time.{Duration, DateTime}
import org.joda.time.format.DateTimeFormat
import controllers.Logging
import scala.Predef._
import collection.mutable
import magenta.DeployParameters
import play.api.libs.concurrent.Promise
import java.net.URLEncoder
import lifecycle.LifecycleWithoutApp

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

  private def apply(buildTypes: String => Option[BuildType], build: Node): Option[Build] = {
    buildTypes(build \ "@buildTypeId" text).map { bt =>
      apply((build \ "@id" text).toInt, bt, build \ "@number" text, build \ "@startDate" text, (build \ "@branchName").headOption.map(_.text).getOrElse("default"))
    }
  }

  def apply(buildTypes: String => Option[BuildType], buildElements: Elem): List[Build] = {
    (buildElements \ "build").toList filter {
      build => (build \ "@status").text == "SUCCESS"
    } flatMap { apply(buildTypes, _) }
  }
}
case class Build(buildId: Int, buildType: BuildType, number: String, startDate: DateTime, branch: String)

object TeamCity extends LifecycleWithoutApp with Logging {
  val dateTimeFormat = DateTimeFormat.forPattern("yyyyMMdd'T'HHmmssZ")
  val pollingWindow = Duration.standardMinutes(conf.Configuration.teamcity.pollingWindowMinutes)
  val pollingPeriod = conf.Configuration.teamcity.pollingPeriodSeconds.seconds
  val fullUpdatePeriod = conf.Configuration.teamcity.fullUpdatePeriodSeconds.seconds

  private val listeners = mutable.Buffer[BuildWatcher]()
  def subscribe(sink: BuildWatcher) { listeners += sink }
  def unsubscribe(sink: BuildWatcher) { listeners -= sink }

  def notifyListeners(newBuilds: List[Build]) {
    listeners.foreach{ listener =>
      try listener.newBuilds(newBuilds)
      catch {
        case e:Exception => log.error("BuildWatcher threw an exception", e)
      }
    }
  }

  object api {
    val projectList = TeamCityWS.url("/app/rest/projects")
    def project(project: String) = TeamCityWS.url("/app/rest/projects/id:%s" format project)
    def buildList(buildTypeId: String) = TeamCityWS.url("/app/rest/builds/?locator=buildType:%s,branch:branched:any" format buildTypeId)
    def buildSince(buildId:Int) = TeamCityWS.url("/app/rest/builds/?locator=sinceBuild:%d,branch:branched:any" format buildId)
    def buildSince(startTime:DateTime) = TeamCityWS.url("/app/rest/builds/?locator=sinceDate:%s,branch:branched:any" format URLEncoder.encode(dateTimeFormat.print(startTime), "UTF-8"))
  }

  private val fullUpdate = PeriodicScheduledAgentUpdate[List[Build]](0 seconds, fullUpdatePeriod) { currentBuilds =>
    val builds = getSuccessfulBuilds.await((1 minute).toMillis).get
    if (!currentBuilds.isEmpty) notifyListeners((builds.toSet diff currentBuilds.toSet).toList)
    builds
  }

  private val incrementalUpdate = PeriodicScheduledAgentUpdate[List[Build]](1 minute, pollingPeriod) { currentBuilds =>
    if (currentBuilds.isEmpty) {
      log.warn("No builds yet, aborting incremental update")
      currentBuilds
    } else {
      getNewBuilds(currentBuilds).map { newBuilds =>
        if (newBuilds.isEmpty)
          currentBuilds
        else {
          notifyListeners(newBuilds)
          (currentBuilds ++ newBuilds).sortBy(-_.buildId)
        }
      }.await((pollingPeriod).toMillis).get
    }
  }

  private var buildAgent:Option[ScheduledAgent[List[Build]]] = None

  def builds: List[Build] = buildAgent.map(_.apply()).getOrElse(Nil)
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


  def init() { buildAgent = Some(ScheduledAgent[List[Build]](List.empty[Build], fullUpdate, incrementalUpdate)) }

  def shutdown() { buildAgent.foreach(_.shutdown()) }

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
    ws.get().map(_.xml).map { Build(Map(buildType.id -> Some(buildType)), _) }
  }

  private def getNewBuilds(currentBuilds: List[Build]): Promise[List[Build]] = {
    val knownBuilds = currentBuilds.map(_.buildId).toSet
    val buildTypeMap = currentBuilds.map(b => b.buildType.id -> b.buildType).toMap
    val getBuildType = (buildTypeId:String) => {
      buildTypeMap.get(buildTypeId).orElse {
        buildAgent.foreach(_.queueUpdate(fullUpdate))
        log.warn("Unknown build type %s, queuing complete refresh" format buildTypeId)
        None
      }
    }
    val pollingWindowStart = (new DateTime()).minus(pollingWindow)
    log.info("Querying TC for all builds since %s" format pollingWindowStart)
    val ws = api.buildSince(pollingWindowStart)
    log.debug("Getting %s" format ws.url)
    ws.get().map(_.xml).map { buildElements =>
      val builds = Build(getBuildType, buildElements)
      log.debug("Found %d builds since %s" format (builds.size, pollingWindowStart))
      val newBuilds = builds.filterNot(build => knownBuilds.contains(build.buildId))
      log.info("Discovered builds: \n%s" format newBuilds.mkString("\n"))
      newBuilds
    }
  }
}

