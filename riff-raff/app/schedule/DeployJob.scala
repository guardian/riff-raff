package schedule

import controllers.Logging
import deployment._
import magenta.{DeployParameters, RunState}
import notification.DeployFailureNotifications
import org.quartz.{Job, JobDataMap, JobExecutionContext}
import schedule.DeployJob.extractDeployParameters
import schedule.DeployScheduler.JobDataKeys
import utils.LogAndSquashBehaviour

import scala.annotation.tailrec
import scala.util.Try

class DeployJob extends Job with Logging {
  private def getAs[T](key: String)(implicit jobDataMap: JobDataMap): T = jobDataMap.get(key).asInstanceOf[T]

  override def execute(context: JobExecutionContext): Unit = {
    implicit val jobDataMap: JobDataMap = context.getJobDetail.getJobDataMap
    val deployments = getAs[Deployments](JobDataKeys.Deployments)
    val projectName = getAs[String](JobDataKeys.ProjectName)
    val stage = getAs[String](JobDataKeys.Stage)
    val scheduledDeploymentEnabled = getAs[Boolean](JobDataKeys.ScheduledDeploymentEnabled)

    DeployJob.getLastDeploy(deployments, projectName, stage) match {
      case Left(error) =>
        log.warn(s"Scheduled deploy failed to start. The last deploy could not be retrieved due to ${error.message}.")
      case Right(record) =>
        val result = for {
          params <- DeployJob.createDeployParameters(record, scheduledDeploymentEnabled)
          uuid <- deployments.deploy(params, ScheduleRequestSource)
        } yield uuid

        result match {
          case Left(error) =>
            val schedulerContext = context.getScheduler.getContext
            val scheduledDeployNotifier: DeployFailureNotifications = schedulerContext.get("scheduledDeployNotifier").asInstanceOf[DeployFailureNotifications]
            log.info(s"Scheduled deploy failed to start due to ${error.message}. Deploy parameters were ${extractDeployParameters(record)}")
            // Once we understand some common reasons for failing to start deploys and the actions needed to resolve the problems
            // we can uncomment the next line to enable these notifications
            scheduledDeployNotifier.failedDeployNotification(None, extractDeployParameters(record), Some(error))
          case Right(uuid) => log.info(s"Started scheduled deploy $uuid")
        }
    }
  }
}

object DeployJob extends Logging with LogAndSquashBehaviour {

  def createDeployParameters(lastDeploy: Record, scheduledDeploysEnabled: Boolean): Either[Error, DeployParameters] = {
    def defaultError(state: RunState): Error = Error(s"Skipping scheduled deploy as deploy record ${lastDeploy.uuid} has status $state")
    lastDeploy.state match {
      case RunState.Completed =>
        val params = extractDeployParameters(lastDeploy)
        if (scheduledDeploysEnabled) {
          Right(params)
        } else {
          Left(Error(s"Scheduled deployments disabled. Would have deployed $params"))
        }
      case RunState.Failed =>
        Left(defaultError(RunState.Failed).copy(scheduledDeployError = Some(SkippedDueToPreviousFailure)))
      case RunState.NotRunning =>
        Left(defaultError(RunState.Failed).copy(scheduledDeployError = Some(SkippedDueToPreviousWaitingDeploy)))
      case otherState =>
        Left(defaultError(otherState))
    }
  }

  private def extractDeployParameters(lastDeploy: Record) = {
    DeployParameters(
      ScheduledDeployer.deployer,
      lastDeploy.parameters.build,
      lastDeploy.stage
    )
  }

  @tailrec
  private def getLastDeploy(deployments: Deployments, projectName: String, stage: String, attempts: Int = 5): Either[Error, Record] = {
    if (attempts == 0) {
      Left(Error(s"Didn't find any deploys for $projectName / $stage", Some(NoDeploysFoundForStage)))
    } else {
      val filter = DeployFilter(
        projectName = Some(projectName),
        stage = Some(stage),
        isExactMatchProjectName = Some(true)
      )
      val pagination = PaginationView().withPageSize(Some(1))

      val result = Try(deployments.getDeploys(Some(filter), pagination).logAndSquashException(Nil).headOption).toOption.flatten
      result match {
        case Some(record) => Right(record)
        case None =>
          Thread.sleep(1000)
          getLastDeploy(deployments, projectName, stage, attempts-1)
      }
    }
  }
}
