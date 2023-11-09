package notification

import com.gu.anghammarad.models.{Action, Target}
import controllers.routes
import deployment.{
  NoDeploysFoundForStage,
  ScheduledDeployNotificationError,
  SkippedDueToPreviousFailure,
  SkippedDueToPreviousWaitingDeploy,
  SkippedDueToPreviousPartialDeploy
}
import magenta.DeployParameters

import java.util.UUID

case class NotificationContents(
    subject: String,
    message: String,
    actions: List[Action]
)
case class NotificationContentsWithTargets(
    notificationContents: NotificationContents,
    targets: List[Target]
)

class FailureNotificationContents(prefix: String) {

  def problematicDeployUrl(uuid: UUID): String = {
    val path = routes.DeployController.viewUUID(uuid.toString, verbose = true)
    prefix + path.url
  }

  def deployAgainUrl(uuid: UUID): String = {
    val path = routes.DeployController.deployAgainUuid(uuid.toString)
    prefix + path.url
  }

  val scheduledDeployConfigUrl: String = {
    val path = routes.ScheduleController.list
    prefix + path.url
  }

  def viewProblematicDeploy(uuid: UUID, status: String) =
    Action(s"View $status deploy", problematicDeployUrl(uuid))

  def midDeployFailureNotificationContents(
      uuid: UUID,
      parameters: DeployParameters
  ): NotificationContents = {
    NotificationContents(
      subject = s"${parameters.deployer.name} failed",
      message =
        s"${parameters.deployer.name} for ${parameters.build.projectName} (build ${parameters.build.id}) to stage ${parameters.stage.name} failed.",
      actions = List(viewProblematicDeploy(uuid, "failed"))
    )
  }

  def scheduledDeployFailureNotificationContents(
      scheduledDeployError: ScheduledDeployNotificationError,
      teamTargetsSearch: (UUID, DeployParameters) => List[Target],
      fallbackTargets: List[Target]
  ): NotificationContentsWithTargets = {
    val subject = "Scheduled Deployment failed to start"
    scheduledDeployError match {
      case SkippedDueToPreviousFailure(record) =>
        val redeployAction =
          Action("Redeploy manually", deployAgainUrl(record.uuid))
        val contents = NotificationContents(
          subject,
          scheduledDeployError.message,
          List(viewProblematicDeploy(record.uuid, "failed"), redeployAction)
        )
        NotificationContentsWithTargets(
          contents,
          teamTargetsSearch(record.uuid, record.parameters)
        )
      case SkippedDueToPreviousPartialDeploy(record) =>
        val redeployAction =
          Action("Redeploy manually", deployAgainUrl(record.uuid))
        val contents = NotificationContents(
          subject,
          scheduledDeployError.message,
          List(viewProblematicDeploy(record.uuid, "partial"), redeployAction)
        )
        NotificationContentsWithTargets(
          contents,
          teamTargetsSearch(record.uuid, record.parameters)
        )
      case SkippedDueToPreviousWaitingDeploy(record) =>
        val contents = NotificationContents(
          subject,
          scheduledDeployError.message,
          List(viewProblematicDeploy(record.uuid, "waiting"))
        )
        NotificationContentsWithTargets(contents, fallbackTargets)
      case NoDeploysFoundForStage(_, _) =>
        val scheduledDeployConfig = Action(
          "View Scheduled Deployment configuration",
          scheduledDeployConfigUrl
        )
        val contents = NotificationContents(
          subject,
          scheduledDeployError.message,
          List(scheduledDeployConfig)
        )
        NotificationContentsWithTargets(contents, fallbackTargets)
    }
  }

}
