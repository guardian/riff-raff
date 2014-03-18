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
import ci.teamcity.{TeamCity, BuildType}
import ci.teamcity.TeamCity.BuildLocator
import play.api.libs.concurrent.Promise
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future
import play.api.libs.ws.Response

object TeamCityBuildPinner extends LifecycleWithoutApp with Logging {

  val pinStages = conf.Configuration.teamcity.pinStages
  val maxPinned = conf.Configuration.teamcity.maximumPinsPerProject
  val pinningEnabled = conf.Configuration.teamcity.pinSuccessfulDeploys
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
      def pin(text: String): Future[Response] = {
        val buildPinCall = TeamCity.api.build.pin(BuildLocator.id(realBuild.id)).put(text)
        buildPinCall.map { response =>
          log.info(s"Pinning build $realBuild: HTTP status code ${response.status}")
          log.debug(s"HTTP response body ${response.body}")
        }
        buildPinCall
      }
      pin(s"Pinned by RiffRaff: ${Configuration.urls.publicPrefix}${routes.Deployment.viewUUID(deployId.toString).url}")
      cleanUpPins(realBuild.buildType)
    } getOrElse {
      log.warn(s"Unable to pin build ${build} as the associated TeamCity build was not known")
    }
  }

  def cleanUpPins(buildType: BuildType) {
    log.debug("Cleaning up any old pins")
    val allPinnedBuilds = BuildLocator.pinned(pinned=true).buildTypeInstance(buildType).list
    allPinnedBuilds.map { builds =>
      log.debug("Found %d pinned builds for %s" format (builds.size, buildType.id))
      if (builds.size > maxPinned) {
        log.debug("Getting pin information")
        Future.sequence(builds.map(_.detail)).map { detailedBuilds =>
          log.debug("Got details for %d builds: %s" format (detailedBuilds.size, detailedBuilds.mkString("\n")))
          detailedBuilds.filter(_.pinInfo.get.user.username == tcUserName).sortBy(-_.pinInfo.get.timestamp.getMillis).drop(maxPinned).map { buildToUnpin =>
            log.debug("Unpinning %s" format buildToUnpin)
            def unpin(): Future[Response] = {
              val buildPinCall = TeamCity.api.build.pin(BuildLocator.id(buildToUnpin.id)).delete()
              buildPinCall.map { response =>
                log.info(s"Unpinning build $buildToUnpin: HTTP status code ${response.status}")
                log.debug(s"HTTP response body ${response.body}")
              }
              buildPinCall
            }
            unpin()
          }
        }
      }
    }
  }

  def init() { sink.foreach(MessageBroker.subscribe) }
  def shutdown() { sink.foreach(MessageBroker.unsubscribe) }
}
