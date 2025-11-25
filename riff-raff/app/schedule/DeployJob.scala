package schedule

import controllers.Logging
import deployment._
import magenta.Strategy.MostlyHarmless
import magenta.input.DeploymentKeysSelector
import magenta.{DeployParameters, RunState}
import notification.DeployFailureNotifications
import org.quartz.{Job, JobDataMap, JobExecutionContext}
import schedule.DeployJob.extractDeployParameters
import schedule.DeployScheduler.JobDataKeys
import utils.LogAndSquashBehaviour

import scala.annotation.tailrec
import scala.util.Try

class DeployJob extends Job with Logging {
  private def getAs[T](key: String)(implicit jobDataMap: JobDataMap): T =
    jobDataMap.get(key).asInstanceOf[T]

  override def execute(context: JobExecutionContext): Unit = {
    implicit val jobDataMap: JobDataMap = context.getJobDetail.getJobDataMap
    val deployments = getAs[Deployments](JobDataKeys.Deployments)
    val projectName = getAs[String](JobDataKeys.ProjectName)
    val stage = getAs[String](JobDataKeys.Stage)
    val scheduledDeploymentEnabled =
      getAs[Boolean](JobDataKeys.ScheduledDeploymentEnabled)

    val schedulerContext = context.getScheduler.getContext
    val scheduledDeployNotifier: DeployFailureNotifications = schedulerContext
      .get("scheduledDeployNotifier")
      .asInstanceOf[DeployFailureNotifications]

    val attemptToStartDeploy = for {
      record <- DeployJob.getLastDeploy(deployments, projectName, stage)
      params <- DeployJob.createDeployParameters(
        record,
        scheduledDeploymentEnabled
      )
      uuid <- deployments.deploy(params, ScheduleRequestSource)
    } yield uuid

    attemptToStartDeploy match {
      case Left(error: ScheduledDeployNotificationError) =>
        log.info(
          s"Scheduled deploy failed to start due to $error. A notification will be sent..."
        )
        scheduledDeployNotifier.scheduledDeployFailureNotification(error)
      case Left(anotherError) =>
        log.warn(
          s"Scheduled deploy failed to start due to $anotherError. A notification will not be sent..."
        )
      case Right(uuid) =>
        log.info(s"Started scheduled deploy $uuid")
    }
  }
}

object DeployJob extends Logging with LogAndSquashBehaviour {

  def createDeployParameters(
      lastDeploy: Record,
      scheduledDeploysEnabled: Boolean
  ): Either[RiffRaffError, DeployParameters] = {
    lastDeploy.state match {
      case RunState.Completed =>
        val params = extractDeployParameters(lastDeploy)
        if (
          lastDeploy.parameters.selector.isInstanceOf[DeploymentKeysSelector]
        ) {
          Left(SkippedDueToPreviousPartialDeploy(lastDeploy))
        } else if (scheduledDeploysEnabled) {
          Right(params)
        } else {
          Left(
            Error(
              s"Scheduled deployments disabled. Would have deployed $params"
            )
          )
        }
      case RunState.Failed =>
        Left(SkippedDueToPreviousFailure(lastDeploy))
      case RunState.NotRunning =>
        Left(SkippedDueToPreviousWaitingDeploy(lastDeploy))
      case otherState =>
        Left(
          Error(
            s"Skipping scheduled deploy as deploy record ${lastDeploy.uuid} has status $otherState"
          )
        )
    }
  }

  private def extractDeployParameters(lastDeploy: Record) = {
    DeployParameters(
      ScheduledDeployer.deployer,
      lastDeploy.parameters.build,
      lastDeploy.stage,
      updateStrategy = MostlyHarmless
    )
  }

  @tailrec
  private def getLastDeploy(
      deployments: Deployments,
      projectName: String,
      stage: String,
      attempts: Int = 5
  ): Either[ScheduledDeployNotificationError, Record] = {
    if (attempts == 0) {
      Left(NoDeploysFoundForStage(projectName, stage))
    } else {
      val filter = DeployFilter(
        projectName = Some(projectName),
        stage = Some(stage),
        isExactMatchProjectName = Some(true)
      )
      val pagination = PaginationView().withPageSize(Some(1))

      val result = Try(
        deployments
          .getDeploys(Some(filter), pagination)
          .logAndSquashException(Nil)
          .headOption
      ).toOption.flatten
      result match {
        case Some(record) => Right(record)
        case None         =>
          Thread.sleep(1000)
          getLastDeploy(deployments, projectName, stage, attempts - 1)
      }
    }
  }
}
