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

import java.util.UUID
import scala.jdk.CollectionConverters._

trait LogMarker {
  def toLogMarker: LogstashMarker = appendEntries(markerContents.asJava)

  def markerContents: Map[String, Any]
}

class GrafanaAnnotationLogger(riffRaffUrl: String)
    extends Lifecycle
    with Logging {

  val messageSub: Subscription = DeployReporter.messages.subscribe(message => {
    val deployId = message.context.deployId
    message.stack.top match {
      case StartContext(Deploy(parameters)) =>
        log.info("Started deploy")(buildMarker(deployId, parameters))
      case FailContext(Deploy(parameters)) =>
        log.info("Failed deploy")(buildMarker(deployId, parameters))
      case FinishContext(Deploy(parameters)) =>
        log.info("Finished deploy")(buildMarker(deployId, parameters))
      case _ =>
    }
  })

  private def buildMarker(
      deployId: UUID,
      parameters: DeployParameters
  ): MarkerContext = {
    val params: Map[String, Any] =
      Map(
        "projectName" -> parameters.build.projectName,
        "projectBuild" -> parameters.build.id,
        "projectStage" -> parameters.stage.name,
        "projectDeployer" -> parameters.deployer.name,
        "projectDeploymentLink" -> s"<a target=\"_blank\" href=\"${riffRaffUrl}/deployment/view/$deployId\">${parameters.build.id}</a>"
      )
    MarkerContext(appendEntries(params.asJava))
  }

  override def init(): Unit = {}

  override def shutdown(): Unit = { messageSub.unsubscribe() }
}
