package notification

import ci.{TeamCityBuilds}
import lifecycle.LifecycleWithoutApp
import magenta._
import controllers.{routes, Logging, DeployController}
import deployment.TaskType
import magenta.MessageWrapper
import magenta.FinishContext
import magenta.Deploy
import scala.Some
import java.net.URLEncoder
import conf.Configuration
import java.util.UUID
import ci.teamcity.{TeamCityWS, TeamCity}

object TeamCityBuildPinner extends LifecycleWithoutApp with Logging {

  val pinningEnabled = conf.Configuration.teamcity.pinSuccessfulDeploys
  val pinStages = conf.Configuration.teamcity.pinStages

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
    val buildType = TeamCityBuilds.buildTypes.find(_.fullName == build.projectName)
    if (buildType.isDefined) {
      val id = buildType.get.id
      val number = URLEncoder.encode(build.id,"UTF-8")
      val buildPinCall = TeamCity.api.build.pin(id, number).put(
        "Pinned by RiffRaff: %s%s" format (Configuration.urls.publicPrefix, routes.Deployment.viewUUID(deployId.toString).url)
      )
      buildPinCall.map { response =>
        log.info("Pinning build %s: HTTP status code %d" format (build.toString, response.status))
        log.debug("HTTP response body %s" format response.body)
      }
      cleanUpPins(id)
    } else {
      log.warn("Unable to pin build %s as the associated TeamCity buildType was not known" format build.toString)
    }
  }

  def cleanUpPins(buildId: String) {
    val allPinnedBuilds = TeamCityWS.url("/app/rest/builds/?locator=buildType:%s,pinned:true" format buildId)
    allPinnedBuilds.get().map { response =>

    }
  }

  def init() { sink.foreach(MessageBroker.subscribe) }
  def shutdown() { sink.foreach(MessageBroker.unsubscribe) }
}
