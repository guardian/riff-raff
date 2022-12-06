package schedule

import java.util.{TimeZone, UUID}

import conf.Config
import controllers.Logging
import deployment.Deployments
import notification.DeployFailureNotifications
import org.quartz.CronScheduleBuilder._
import org.quartz.JobBuilder._
import org.quartz.TriggerBuilder._
import org.quartz.impl.StdSchedulerFactory
import org.quartz.{JobDataMap, JobKey, TriggerKey}
import schedule.DeployScheduler.JobDataKeys

class DeployScheduler(
    config: Config,
    deployments: Deployments,
    scheduledDeployNotifier: DeployFailureNotifications
) extends Logging {

  private val scheduler = StdSchedulerFactory.getDefaultScheduler

  def initialise(schedules: Iterable[ScheduleConfig]): Unit = {
    schedules.foreach(scheduleDeploy)
  }

  def reschedule(schedule: ScheduleConfig): Unit = {
    // Delete any job and trigger that we may have previously created
    cancel(schedule.id)
    scheduleDeploy(schedule)
  }

  def cancel(id: UUID): Unit = {
    scheduler.deleteJob(jobKey(id))
  }

  private def scheduleDeploy(scheduleConfig: ScheduleConfig): Unit = {
    val id = scheduleConfig.id
    if (scheduleConfig.enabled) {
      val jobDetail = newJob(classOf[DeployJob])
        .withIdentity(jobKey(id))
        .usingJobData(buildJobDataMap(scheduleConfig))
        .build()

      scheduler.getContext.put(
        "scheduledDeployNotifier",
        scheduledDeployNotifier
      )

      val trigger = newTrigger()
        .withIdentity(triggerKey(id))
        .withSchedule(
          cronSchedule(scheduleConfig.scheduleExpression)
            .inTimeZone(TimeZone.getTimeZone(scheduleConfig.timezone))
        )
        .build()
      scheduler.scheduleJob(jobDetail, trigger)
      log.info(
        s"Scheduled [$id] to deploy with schedule [${scheduleConfig.scheduleExpression} in ${scheduleConfig.timezone}]"
      )
    } else {
      log.info(
        s"NOT scheduling disabled schedule [$id] to deploy with schedule [${scheduleConfig.scheduleExpression} in ${scheduleConfig.timezone}]"
      )
    }
  }

  def start(): Unit = scheduler.start()

  def shutdown(): Unit = scheduler.shutdown()

  private def jobKey(id: UUID): JobKey = new JobKey(id.toString)
  private def triggerKey(id: UUID): TriggerKey = new TriggerKey(id.toString)

  private def buildJobDataMap(scheduleConfig: ScheduleConfig): JobDataMap = {
    val map = new JobDataMap()
    map.put(JobDataKeys.Deployments, deployments)
    map.put(
      JobDataKeys.ScheduledDeploymentEnabled,
      config.scheduledDeployment.enabled
    )
    map.put(JobDataKeys.ProjectName, scheduleConfig.projectName)
    map.put(JobDataKeys.Stage, scheduleConfig.stage)
    map
  }

}

object DeployScheduler {

  object JobDataKeys {
    val Deployments = "deployments"
    val ScheduledDeploymentEnabled = "ScheduledDeploymentEnabled"
    val ProjectName = "projectName"
    val Stage = "stage"
  }

}
