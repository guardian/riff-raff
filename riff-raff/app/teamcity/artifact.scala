package teamcity

import java.net.URL
import conf.Configuration
import xml.XML
import utils.ScheduledAgent
import akka.util.duration._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import controllers.Logging

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
  def name: String
  def number: String
  def startDate: DateTime
}

case class TeamCityBuildType(id: String, name: String) extends BuildType

object TeamCityBuild {
  val dateTimeParser = DateTimeFormat.forPattern("yyyyMMdd'T'HHmmssZ")

  def apply(name: String, number: String, startDate: String): TeamCityBuild = {
    val parsedStartDate: DateTime = dateTimeParser.parseDateTime(startDate)
    apply(name, number, parsedStartDate)
  }
}
case class TeamCityBuild(name: String, number: String, startDate: DateTime) extends Build

object TeamCity extends BuildServer with Logging {
  val tcURL = Configuration.teamcity.serverURL
  object api {
    val projectList = "/guestAuth/app/rest/projects"
    val buildList = "/guestAuth/app/rest/builds/?locator=buildType:%s"
  }

  private val buildAgent = ScheduledAgent[Map[BuildType,List[Build]]](0 seconds, 1 minute, Map.empty[BuildType,List[Build]]){ _ =>
    log.info("Querying TC for build types")
    val buildTypes = getRetrieveBuildTypes
    log.info("Querying TC for all successful builds")
    val result = getSuccessfulBuildMap(buildTypes)
    log.info("Finished updating TC information (found %d buildTypes and %d successful builds)" format(result.size, result.values.map(_.size).reduce(_+_)))
    result
  }

  def buildMap = buildAgent()
  def buildTypes = buildMap.keys.toList

  def successfulBuilds(projectName: String): List[Build] = buildTypes.filter(_.name == projectName).headOption
    .flatMap(buildMap.get(_)).getOrElse(Nil)

  private def getRetrieveBuildTypes: List[BuildType] = {
    val projectElements = XML.load(new URL(tcURL,api.projectList))
    (projectElements \ "project").toList.flatMap { project =>
      val buildTypeElements = XML.load(new URL(tcURL,(project \ "@href").text))
      (buildTypeElements \\ "buildType").map { buildType =>
        TeamCityBuildType(buildType \ "@id" text, "%s::%s" format(buildType \ "@projectName" text, buildType \ "@name" text))
      }
    }
  }

  private def getSuccessfulBuildMap(buildTypes: List[BuildType]): Map[BuildType,List[Build]] = {
    buildTypes.map(buildType => buildType -> getSuccessfulBuilds(buildType)).toMap
  }

  private def getSuccessfulBuilds(buildType: BuildType): List[Build] = {
    val url = new URL(tcURL, api.buildList format buildType.id)
    log.debug("Getting %s" format url.toString)
    val buildElements = XML.load(url)
    (buildElements \ "build").toList filter { build => (build \ "@status").text == "SUCCESS" } map { build =>
      TeamCityBuild(buildType.name, build \ "@number" text, build \ "@startDate" text)
    }
  }
}

