package notification

import java.util.UUID
import ci.TargetResolver
import controllers.{Logging, routes}
import lifecycle.Lifecycle
import magenta.{DeployParameters, DeployReporter, Fail}
import scala.concurrent.ExecutionContext
import com.gu.anghammarad.Anghammarad
import com.gu.anghammarad.models._
import conf.Configuration
import magenta.deployment_type.DeploymentType
import magenta.input.resolver.Resolver
import schedule.ScheduledDeployer

class ScheduledDeployFailureNotifications(deploymentTypes: Seq[DeploymentType])(implicit ec: ExecutionContext) extends Lifecycle with Logging {

  private val anghammaradTopicARN = Configuration.scheduledDeployment.anghammaradTopicARN
  private val snsClient = Configuration.scheduledDeployment.snsClient
  private val prefix = Configuration.urls.publicPrefix

  def url(uuid: UUID): String = {
    val path = routes.DeployController.viewUUID(uuid.toString,true)
    prefix + path.url
  }

  def failedDeployNotification(uuid: UUID, parameters: DeployParameters) = {
    val deriveAnghammaradTargets = for {
      yaml <- TargetResolver.fetchYaml(parameters.build)
      deployGraph <- Resolver.resolveDeploymentGraph(yaml, deploymentTypes, magenta.input.All).toEither
      magentaTargets = TargetResolver.extractTargets(deployGraph)
    } yield {
      magentaTargets.toList.flatMap { target =>
        List(App(target.app), Stack(target.stack))
      } ++ List(Stage(parameters.stage.name))
    }

    deriveAnghammaradTargets match {
      case Right(targets) =>
        val failureMessage = s"Your scheduled deploy failed. This means that your AMI may become out of date. If no successful deploys occur between now and the next scheduled attempt, it will not be retried."
        Anghammarad.notify(
          subject = "Scheduled deploy failed",
          message = failureMessage,
          sourceSystem = "riff-raff",
          channel = All,
          target = targets,
          actions = List(Action("View failed deploy", url(uuid))),
          topicArn = anghammaradTopicARN,
          client = snsClient
        ).recover { case ex => log.error(s"Failed to send notification (via Anghammarad) for ${uuid}", ex) }
      case Left(_) =>
        log.error(s"Failed to derive targets required to notify about failed scheduled deploy: ${uuid}")
    }
  }

  def scheduledDeploy(deployParameters: DeployParameters): Boolean = deployParameters.deployer == ScheduledDeployer.deployer

  val messageSub = DeployReporter.messages.subscribe(message => {
    message.stack.top match {
      case Fail(_, _, parameters) if scheduledDeploy(parameters) =>
        log.info(s"Attempting to send notification via Anghammarad")
        failedDeployNotification(message.context.deployId, parameters)
      case _ =>
    }
  })

  def init() {}

  def shutdown() {
    messageSub.unsubscribe()
  }


}
