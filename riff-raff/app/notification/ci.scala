package notification

import ci.TeamCityBuilds
import lifecycle.LifecycleWithoutApp
import magenta._
import controllers.{routes, Logging, DeployController}
import deployment.TaskType
import magenta.MessageWrapper
import magenta.FinishContext
import magenta.Deploy
import scala.Some
import conf.Configuration
import java.util.UUID
import ci.teamcity.BuildType
import ci.teamcity.TeamCity.BuildLocator
import play.api.libs.concurrent.Promise

object TeamCityBuildPinner extends LifecycleWithoutApp with Logging {

  val pinningEnabled = conf.Configuration.teamcity.pinSuccessfulDeploys
  val pinStages = conf.Configuration.teamcity.pinStages
  val maxPinned = conf.Configuration.teamcity.maximumPinsPerProject
  lazy val tcUserName = conf.Configuration.teamcity.user.get

  val sink = if (!pinningEnabled) None else Some(new MessageSink {
    def message(message: MessageWrapper) {
      if (DeployController.get(message.context.deployId).taskType == TaskType.Deploy)
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
      realBuild.pin("Pinned by RiffRaff: %s%s" format (Configuration.urls.publicPrefix, routes.Deployment.viewUUID(deployId.toString).url))
      cleanUpPins(realBuild.buildType)
    } getOrElse {
      log.warn("Unable to pin build %s as the associated TeamCity build was not known" format build.toString)
    }
  }

  def cleanUpPins(buildType: BuildType) {
    log.debug("Cleaning up any old pins")
    val allPinnedBuilds = BuildLocator.pinned(pinned=true).buildTypeInstance(buildType).list
    allPinnedBuilds.map { builds =>
      log.debug("Found %d pinned builds for %s" format (builds.size, buildType.id))
      if (builds.size > maxPinned) {
        log.debug("Getting pin information")
        Promise.sequence(builds.map(_.detail)).map { detailedBuilds =>
          log.debug("Got details for %d builds: %s" format (detailedBuilds.size, detailedBuilds.mkString("\n")))
          detailedBuilds.filter(_.pinInfo.get.user.username == tcUserName).sortBy(-_.pinInfo.get.timestamp.getMillis).drop(maxPinned).map { buildToUnpin =>
            log.debug("Unpinning %s" format buildToUnpin)
            buildToUnpin.unpin()
          }
        }
      }
    }
  }

  def init() { sink.foreach(MessageBroker.subscribe) }
  def shutdown() { sink.foreach(MessageBroker.unsubscribe) }
}
