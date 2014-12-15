package notification

import java.util.UUID

import ci.teamcity.TeamCity.BuildLocator
import ci.teamcity.{BuildType, Project, TeamCity, TeamcityBuild}
import ci.{CIBuild, TeamCityBuilds}
import conf.Configuration
import controllers.{Logging, routes}
import lifecycle.LifecycleWithoutApp
import magenta.{Build, Deploy, FinishContext, MessageBroker, MessageSink, MessageWrapper}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.ws.WSResponse

import scala.concurrent.Future

object TeamCityBuildPinner extends LifecycleWithoutApp with Logging {

  val pinStages = conf.Configuration.teamcity.pinStages
  val maxPinned = conf.Configuration.teamcity.maximumPinsPerProject
  val pinningEnabled = conf.Configuration.teamcity.pinSuccessfulDeploys
  lazy val tcUserName = conf.Configuration.teamcity.user.get

  val sink = if (!pinningEnabled) None else Some(new MessageSink {
    def message(message: MessageWrapper) {
      message.stack.top match {
        case FinishContext(Deploy(parameters)) =>
          if (pinStages.isEmpty || pinStages.contains(parameters.stage.name))
            pinBuild(message.context.deployId, parameters.build)
        case _ =>
      }
    }
  })

  def pinBuild(deployId: UUID, build: Build) {
    log.info("Pinning build %s" format build.toString)
    val tcBuild = TeamCityBuilds.build(build.projectName,build.id)
    tcBuild.map { realBuild =>
      pin(realBuild, "Pinned by RiffRaff: %s%s" format (Configuration.urls.publicPrefix, routes.Deployment.viewUUID(deployId.toString).url))
      cleanUpPins(realBuild)
    } getOrElse {
      log.warn("Unable to pin build %s as the associated TeamCity build was not known" format build.toString)
    }
  }

  def cleanUpPins(build: CIBuild) {
    log.debug("Cleaning up any old pins")
    val buildType = BuildType(
      id = build.jobId, typeName = build.jobName, project = Project(build.jobId, build.jobName))
    val allPinnedBuilds = BuildLocator.pinned(pinned=true).buildTypeInstance(buildType).list
    allPinnedBuilds.map { builds =>
      log.debug("Found %d pinned builds for %s" format (builds.size, build.jobId))
      if (builds.size > maxPinned) {
        log.debug("Getting pin information")
        Future.sequence(builds.map(_.detail)).map { detailedBuilds =>
          log.debug("Got details for %d builds: %s" format (detailedBuilds.size, detailedBuilds.mkString("\n")))
          detailedBuilds.filter(_.pinInfo.get.user.username == tcUserName).sortBy(-_.pinInfo.get.timestamp.getMillis).drop(maxPinned).map { buildToUnpin =>
            log.debug("Unpinning %s" format buildToUnpin)
            unpin(buildToUnpin)
          }
        }
      }
    }
  }

  def pin(build: CIBuild, text: String): Future[WSResponse] = {
    val buildPinCall = TeamCity.api.build.pin(BuildLocator.id(build.id)).put(text)
    buildPinCall.map { response =>
      log.info("Pinning build %s: HTTP status code %d" format (this.toString, response.status))
      log.debug("HTTP response body %s" format response.body)
    }
    buildPinCall
  }
  def unpin(build: TeamcityBuild): Future[WSResponse] = {
    val buildPinCall = TeamCity.api.build.pin(BuildLocator.id(build.id)).delete()
    buildPinCall.map { response =>
      log.info("Unpinning build %s: HTTP status code %d" format (build.toString, response.status))
      log.debug("HTTP response body %s" format response.body)
    }
    buildPinCall
  }

  def init() { sink.foreach(MessageBroker.subscribe) }
  def shutdown() { sink.foreach(MessageBroker.unsubscribe) }
}
