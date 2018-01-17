package schedule

import conf.Configuration
import controllers.Logging
import deployment._
import magenta.{Build, Deployer, DeployParameters, Stage}
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

    getLastDeploy(deployments, projectName, stage) match {
      case Left(error) => log.warn(error)
      case Right(record) =>
        val params = DeployParameters(
          Deployer("Scheduled Deployment"),
          Build(projectName, record.parameters.build.id),
          Stage(stage)
        )
        if (Configuration.scheduledDeployment.enabled) {
          deployments.deploy(params, ScheduleRequestSource)
        } else {
          log.info(s"Scheduled deployments disabled. Would have deployed $params")
        }
    }
  }

  @tailrec
  private def getLastDeploy(deployments: Deployments, projectName: String, stage: String, attempts: Int = 5): Either[String, Record] = {
    if (attempts == 0) {
      Left(s"Didn't find any deploys for $projectName / $stage")
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
