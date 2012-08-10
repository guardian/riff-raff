package teamcity

import java.net.URL
import conf.Configuration
import xml.{Node, Elem, XML}
import utils.ScheduledAgent
import akka.util.duration._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import controllers.Logging
import math.max

trait Artifact {
  def location: URL
  def build: Build
}

trait BuildType {
  def name: String
  def id: String
}

trait BuildServer {

}

trait Build {
  def buildId: Int
  def name: String
  def number: String
  def startDate: DateTime
}

case class TeamCityBuildType(id: String, name: String) extends BuildType

object TeamCityBuild {
  val dateTimeParser = DateTimeFormat.forPattern("yyyyMMdd'T'HHmmssZ")

  def apply(buildId: Int, name: String, number: String, startDate: String): TeamCityBuild = {
    val parsedStartDate: DateTime = dateTimeParser.parseDateTime(startDate)
    apply(buildId: Int, name, number, parsedStartDate)
  }

  def apply(build: Node): TeamCityBuild = {
    apply((build \ "@id" text).toInt, build \ "@buildTypeId" text, build \ "@number" text, build \ "@startDate" text)
  }

  def apply(buildElements: Elem): List[TeamCityBuild] = {
    (buildElements \ "build").toList filter {
      build => (build \ "@status").text == "SUCCESS"
    } map { apply(_) }
  }
}
case class TeamCityBuild(buildId: Int, name: String, number: String, startDate: DateTime) extends Build

object TeamCity extends BuildServer with Logging {
  implicit def buildTypeBuildsMap2latestBuildId(buildTypeMap: Map[BuildType,List[Build]]) = new {
    def latestBuildId(): Int = buildTypeMap.values.flatMap(_.map(_.buildId)).max
  }

  val tcURL = Configuration.teamcity.serverURL
  object api {
    val projectList = "/guestAuth/app/rest/projects"
    def buildList(buildTypeId: String) = "/guestAuth/app/rest/builds/?locator=buildType:%s" format buildTypeId
    def buildSince(buildId:Int) = "/guestAuth/app/rest/builds/?locator=sinceBuild:%d" format buildId
  }

  private val buildAgent = ScheduledAgent[Map[BuildType,List[Build]]](0 seconds, 1 minute, Map.empty[BuildType,List[Build]]){ currentBuildMap =>
    if (currentBuildMap.isEmpty || !getBuildsSince(currentBuildMap.latestBuildId()).isEmpty) {
      val buildTypes = getRetrieveBuildTypes
      val result = getSuccessfulBuildMap(buildTypes)
      log.info("Finished updating TC information (found %d buildTypes and %d successful builds)" format(result.size, result.values.map(_.size).reduce(_+_)))
      result
    } else currentBuildMap
  }

  def buildMap = buildAgent()
  def buildTypes = buildMap.keys.toList

  def successfulBuilds(projectName: String): List[Build] = buildTypes.filter(_.name == projectName).headOption
    .flatMap(buildMap.get(_)).getOrElse(Nil)

  private def getRetrieveBuildTypes: List[BuildType] = {
    log.info("Querying TC for build types")
    val projectElements = XML.load(new URL(tcURL,api.projectList))
    (projectElements \ "project").toList.flatMap { project =>
      val buildTypeElements = XML.load(new URL(tcURL,(project \ "@href").text))
      (buildTypeElements \\ "buildType").map { buildType =>
        TeamCityBuildType(buildType \ "@id" text, "%s::%s" format(buildType \ "@projectName" text, buildType \ "@name" text))
      }
    }
  }

  private def getSuccessfulBuildMap(buildTypes: List[BuildType]): Map[BuildType,List[Build]] = {
    log.info("Querying TC for all successful builds")
    buildTypes.map(buildType => buildType -> getSuccessfulBuilds(buildType)).toMap
  }

  private def getSuccessfulBuilds(buildType: BuildType): List[Build] = {
    val url = new URL(tcURL, api.buildList(buildType.id))
    log.debug("Getting %s" format url.toString)
    val buildElements = XML.load(url)
    TeamCityBuild(buildElements)
  }

  private def getBuildsSince(buildId:Int): List[Build] = {
    log.info("Querying TC for all builds since %d" format buildId)
    val url = new URL(tcURL, api.buildSince(buildId))
    log.debug("Getting %s" format url.toString)
    val buildElements = XML.load(url)
    val builds = TeamCityBuild(buildElements)
    log.info("Found %d builds since %d" format (builds.size, buildId))
    builds
  }
}

