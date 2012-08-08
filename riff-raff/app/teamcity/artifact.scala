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

  val buildTypeAgent = ScheduledAgent[Seq[BuildType]](1 second, 1 minute)(getRetrieveBuildTypes)
  val buildAgent = ScheduledAgent[Map[BuildType,Seq[Build]]](20 seconds, 1 minute){
    log.info("Updating successful TeamCity builds")
    val result = getSuccessfulBuildMap(retrieveBuildTypes)
    log.info("Finished updating successful builds (found %d builds)" format result.values.map(_.size).reduce(_+_))
    result
  }

  def retrieveBuildTypes = buildTypeAgent()
  def successfulBuilds(projectName: String): List[Build] = retrieveBuildTypes.filter(_.name == projectName).headOption
    .map(successfulBuildMap(_).toList).getOrElse(Nil)

  def successfulBuildMap = buildAgent()

  private def getRetrieveBuildTypes: Seq[BuildType] = {
    val projectElements = XML.load(new URL(tcURL,api.projectList))
    (projectElements \ "project").flatMap { project =>
      val buildTypeElements = XML.load(new URL(tcURL,(project \ "@href").text))
      (buildTypeElements \\ "buildType").map { buildType =>
        TeamCityBuildType(buildType \ "@id" text, "%s::%s" format(buildType \ "@projectName" text, buildType \ "@name" text))
      }
    }
  }

  private def getSuccessfulBuildMap(buildTypes: Seq[BuildType]): Map[BuildType,Seq[Build]] = {
    buildTypes.map(buildType => buildType -> getSuccessfulBuilds(buildType)).toMap
  }

  private def getSuccessfulBuilds(buildType: BuildType): Seq[Build] = {
    val url = new URL(tcURL, api.buildList format buildType.id)
    log.debug("Getting %s" format url.toString)
    val buildElements = XML.load(url)
    (buildElements \ "build") filter { build => (build \ "@status").text == "SUCCESS" } map { build =>
      TeamCityBuild(buildType.name, build \ "@number" text, build \ "@startDate" text)
    }
  }
}

