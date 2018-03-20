package schedule

import conf.Configuration
import controllers.Logging
import deployment._
import magenta.{DeployParameters, Deployer, RunState}
import org.joda.time.DateTime
import org.quartz.{Job, JobDataMap, JobExecutionContext}
import schedule.DeployScheduler.JobDataKeys

import scala.annotation.tailrec
import scala.util.Try

class DeployJob extends Job with Logging {
  private def getAs[T](key: String)(implicit jobDataMap: JobDataMap): T = jobDataMap.get(key).asInstanceOf[T]

  override def execute(context: JobExecutionContext): Unit = {
    implicit val jobDataMap = context.getJobDetail.getJobDataMap
    val deployments = getAs[Deployments](JobDataKeys.Deployments)
    val projectName = getAs[String](JobDataKeys.ProjectName)
    val stage = getAs[String](JobDataKeys.Stage)

    val now = new DateTime()
    val cooldownDays = getAs[Option[Int]](JobDataKeys.CoolDownDays)

    val result = for {
      record <- DeployJob.getLastDeploy(deployments, projectName, stage)
      params <- DeployJob.createDeployParameters(record, Configuration.scheduledDeployment.enabled, now, cooldownDays)
      uuid <- deployments.deploy(params, ScheduleRequestSource)
    } yield uuid
    result match {
      case Left(error) => log.warn(error.message)
      case Right(uuid) => log.info(s"Started scheduled deploy $uuid")
    }
  }
}

object DeployJob {
  def createDeployParameters(lastDeploy: Record, scheduledDeploysEnabled: Boolean, now: DateTime, maybeCooldownDays: Option[Int]): Either[Error, DeployParameters] = {
    val params = DeployParameters(
      Deployer("Scheduled Deployment"),
      lastDeploy.parameters.build,
      lastDeploy.stage
    )

    (lastDeploy.state, maybeCooldownDays) match {
      case (RunState.Completed, _) if !scheduledDeploysEnabled =>
        Left(Error(s"Scheduled deployments disabled. Would have deployed $params"))

      case (RunState.Completed, Some(cooldownDays)) if withinCooldown(now, lastDeploy.time, cooldownDays) =>
        Left(Error(s"Scheduled deployment suppressed due to $cooldownDays days cooldown period. Would have deployed $params"))

      case (RunState.Completed, _) =>
        Right(params)

      case (otherState, _) =>
        Left(Error(s"Skipping scheduled deploy as deploy record ${lastDeploy.uuid} has status $otherState"))
    }
  }

  private def withinCooldown(now: DateTime, lastDeployTime: DateTime, cooldownDays: Int): Boolean = {
    lastDeployTime.plusDays(cooldownDays).isAfter(now)
  }

  @tailrec
  private def getLastDeploy(deployments: Deployments, projectName: String, stage: String, attempts: Int = 5): Either[Error, Record] = {
    if (attempts == 0) {
      Left(Error(s"Didn't find any deploys for $projectName / $stage"))
    } else {
      val filter = DeployFilter(
        projectName = Some(projectName),
        stage = Some(stage)
      )
      val pagination = PaginationView().withPageSize(Some(1))

      val result = Try(deployments.getDeploys(Some(filter), pagination).headOption).toOption.flatten
      result match {
        case Some(record) => Right(record)
        case None =>
          Thread.sleep(1000)
          getLastDeploy(deployments, projectName, stage, attempts-1)
      }
    }
  }
}
