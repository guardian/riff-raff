package teamcity

import java.net.URL
import conf.Configuration
import xml.XML
import utils.ScheduledAgent
import akka.util.duration._
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

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

object TeamCity extends BuildServer {
  val tcURL = Configuration.teamcity.serverURL
  object api {
    val projectList = "/guestAuth/app/rest/projects"
    val buildList = "/guestAuth/app/rest/builds/?locator=buildType:%s"
  }

  val buildTypeAgent = ScheduledAgent[Seq[BuildType]](1 minute, 1 minute)(getRetrieveBuildTypes)

  def retrieveBuildTypes = buildTypeAgent()
  def successfulBuilds(projectName: String): List[Build] = retrieveBuildTypes.filter(_.name == projectName).headOption
    .map(successfulBuilds(_).toList).getOrElse(Nil)

  private def getRetrieveBuildTypes: Seq[BuildType] = {
    val projectElements = XML.load(new URL(tcURL,api.projectList))
    (projectElements \ "project").flatMap { project =>
      val buildTypeElements = XML.load(new URL(tcURL,(project \ "@href").text))
      (buildTypeElements \\ "buildType").map { buildType =>
        TeamCityBuildType(buildType \ "@id" text, "%s::%s" format(buildType \ "@projectName" text, buildType \ "@name" text))
      }
    }
  }

  def successfulBuilds(buildType: BuildType): Seq[Build] = {
    val buildElements = XML.load(new URL(tcURL, api.buildList format buildType.id))
    (buildElements \ "build") filter { build => (build \ "@status").text == "SUCCESS" } map { build =>
      TeamCityBuild(buildType.name, build \ "@number" text, build \ "@startDate" text)
    }
  }
}

