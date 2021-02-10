package notification

import com.gu.anghammarad.models.{Action, Target}
import controllers.routes
import deployment.{NoDeploysFoundForStage, ScheduledDeployNotificationError, SkippedDueToPreviousFailure, SkippedDueToPreviousWaitingDeploy}
import magenta.DeployParameters

import java.util.UUID

object FailureNotificationContents {

  case class NotificationContents(subject: String, message: String, actions: List[Action])
  case class NotificationContentsWithTargets(notificationContents: NotificationContents, targets: List[Target])

  def problematicDeployUrl(prefix: String, uuid: UUID): String = {
    val path = routes.DeployController.viewUUID(uuid.toString, verbose = true)
    prefix + path.url
  }

  def deployAgainUrl(prefix: String, uuid: UUID): String = {
    val path = routes.DeployController.deployAgainUuid(uuid.toString)
    prefix + path.url
  }

  def scheduledDeployConfigUrl(prefix: String): String = {
    val path = routes.ScheduleController.list()
    prefix + path.url
  }

  def viewProblematicDeploy(uuid: UUID, status: String, urlPrefix: String) = Action(s"View $status deploy", problematicDeployUrl(urlPrefix, uuid))

  def deployFailedNotificationContents(uuid: UUID, parameters: DeployParameters, urlPrefix: String): NotificationContents = {
    NotificationContents(
      subject = s"${parameters.deployer.name} failed",
      message = s"${parameters.deployer.name} for ${parameters.build.projectName} (build ${parameters.build.id}) to stage ${parameters.stage.name} failed.",
      actions = List(viewProblematicDeploy(uuid, "failed", urlPrefix))
    )
  }

  def deployUnstartedNotificationContents(scheduledDeployError: ScheduledDeployNotificationError, urlPrefix: String, teamTargetsSearch: (UUID, DeployParameters) => List[Target], riffRaffTargets: List[Target]): NotificationContentsWithTargets = {
    val subject = "Scheduled Deployment failed to start"
    scheduledDeployError match {
      case SkippedDueToPreviousFailure(record) =>
        val redeployAction = Action("Redeploy manually", deployAgainUrl(urlPrefix, record.uuid))
        val contents = NotificationContents(subject, scheduledDeployError.message, List(viewProblematicDeploy(record.uuid, "failed", urlPrefix), redeployAction))
        NotificationContentsWithTargets(contents, teamTargetsSearch(record.uuid, record.parameters))
      case SkippedDueToPreviousWaitingDeploy(record) =>
        val contents = NotificationContents(subject, scheduledDeployError.message, List(viewProblematicDeploy(record.uuid, "waiting", urlPrefix)))
        NotificationContentsWithTargets(contents, riffRaffTargets)
      case NoDeploysFoundForStage(_, _) =>
        val scheduledDeployConfig = Action("View Scheduled Deployment configuration", scheduledDeployConfigUrl(urlPrefix))
        val contents = NotificationContents(subject, scheduledDeployError.message, List(scheduledDeployConfig))
        NotificationContentsWithTargets(contents, riffRaffTargets)
    }
  }

}
