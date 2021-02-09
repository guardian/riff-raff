package notification

import com.gu.anghammarad.models.{Action, Target}
import controllers.routes
import deployment.{NoDeploysFoundForStage, ScheduledDeployError, SkippedDueToPreviousFailure, SkippedDueToPreviousWaitingDeploy}
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

  def deployUnstartedNotificationContents(scheduledDeployError: ScheduledDeployError, urlPrefix: String, teamTargetsSearch: (Option[UUID], Option[DeployParameters]) => List[Target], riffRaffTargets: List[Target]): NotificationContentsWithTargets = {
    val subject = "Scheduled Deployment failed to start"
    scheduledDeployError match {
      case SkippedDueToPreviousFailure(record) =>
        val message = s"Scheduled Deployment for ${record.parameters.build.projectName} to ${record.parameters.stage.name} didn't start because the most recent deploy failed."
        val redeployAction = Action("Redeploy manually", deployAgainUrl(urlPrefix, record.uuid))
        val contents = NotificationContents(subject, message, List(viewProblematicDeploy(record.uuid, "failed", urlPrefix), redeployAction))
        NotificationContentsWithTargets(contents, teamTargetsSearch(Some(record.uuid), Some(record.parameters)))
      case SkippedDueToPreviousWaitingDeploy(record) =>
        val message = s"Scheduled Deployment for ${record.parameters.build.projectName} to ${record.parameters.stage.name} failed to start as a previous deploy was still waiting to be deployed."
        val contents = NotificationContents(subject, message, List(viewProblematicDeploy(record.uuid, "waiting", urlPrefix)))
        NotificationContentsWithTargets(contents, riffRaffTargets)
      case NoDeploysFoundForStage(projectName, stage) =>
        val message = s"A scheduled deploy didn't start because Riff-Raff has never deployed $projectName to $stage before. " +
          "Please inform the owner of this schedule as it's likely that they have made a configuration error."
        val scheduledDeployConfig = Action("View Scheduled Deployment configuration", scheduledDeployConfigUrl(urlPrefix))
        val contents = NotificationContents(subject, message, List(scheduledDeployConfig))
        NotificationContentsWithTargets(contents, riffRaffTargets)
    }
  }

}
