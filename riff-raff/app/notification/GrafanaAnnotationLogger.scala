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
    val top = message.stack.top
    log.info(
      s"Received message with stack top: ${top} of type: ${top.getClass}"
    )

    top match {
      case sc: StartContext =>
        log.info(s"Matched StartContext: $sc")
        sc match {
          case StartContext(Deploy(parameters)) =>
            log.info("Started deploy")(buildMarker(parameters))
          case _ =>
            log.info(s"Unexpected StartContext: $sc")
        }
      case fc: FailContext =>
        log.info(s"Matched FailContext: $fc")
        fc match {
          case FailContext(Deploy(parameters)) =>
            log.info("Failed deploy")(buildMarker(parameters))
          case _ =>
            log.info(s"Unexpected FailContext: $fc")
        }
      case fsc: FinishContext =>
        log.info(s"Matched FinishContext: $fsc")
        fsc match {
          case FinishContext(Deploy(parameters)) =>
            log.info("Finished deploy")(buildMarker(parameters))
          case _ =>
            log.info(s"Unexpected FinishContext: $fsc")
        }
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
