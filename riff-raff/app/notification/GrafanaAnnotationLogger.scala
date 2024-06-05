package notification

import controllers.Logging
import lifecycle.Lifecycle
import magenta.ContextMessage.{FailContext, FinishContext, StartContext}
import magenta.{DeployParameters, DeployReporter}
import magenta.Message.Deploy
import net.logstash.logback.marker.LogstashMarker
import net.logstash.logback.marker.Markers.appendEntries
import play.api.MarkerContext
import rx.lang.scala.Subscription

import scala.jdk.CollectionConverters._

trait LogMarker {
  def toLogMarker: LogstashMarker = appendEntries(markerContents.asJava)

  def markerContents: Map[String, Any]
}

class GrafanaAnnotationLogger extends Lifecycle with Logging {

  val messageSub: Subscription = DeployReporter.messages.subscribe(message => {
    message.stack.top match {
      case StartContext(Deploy(parameters)) =>
        log.info("Started deploy")(buildMarker(parameters))
      case FailContext(Deploy(parameters)) =>
        log.info("Failed deploy")(buildMarker(parameters))
      case FinishContext(Deploy(parameters)) =>
        log.info("Finished deploy")(buildMarker(parameters))
      case _ =>
        log.info(
          s"Didn't match start/fail/finish context: ${message.stack.top}"
        )
    }
  })

  private def buildMarker(parameters: DeployParameters): MarkerContext = {
    val params: Map[String, Any] =
      Map(
        "projectName" -> parameters.build.projectName,
        "projectBuild" -> parameters.build.id,
        "projectStage" -> parameters.stage.name,
        "projectDeployer" -> parameters.deployer.name
      )
    MarkerContext(appendEntries(params.asJava))
  }

  override def init(): Unit = {
    log.info("Initialised grafana annotation logger")
  }

  override def shutdown(): Unit = { messageSub.unsubscribe() }
}
