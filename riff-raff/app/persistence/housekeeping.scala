package persistence

import lifecycle.Lifecycle
import conf.Configuration.housekeeping
import org.joda.time.{LocalDate, LocalTime}
import persistence.Persistence.store
import utils.{DailyScheduledAgentUpdate, ScheduledAgent}
import controllers.Logging

object SummariseDeploysHousekeeping extends Lifecycle with Logging {
  lazy val maxAgeDays = housekeeping.summariseDeploysAfterDays
  lazy val housekeepingTime = new LocalTime(housekeeping.hour, housekeeping.minute)

  def summariseDeploys(): Int = {
    log.info("Summarising deploys older than %d days" format maxAgeDays)
    val maxAgeThreshold = (LocalDate.now()).minusDays(maxAgeDays)
    val deploys = store.getCompleteDeploysOlderThan(maxAgeThreshold.toDateTimeAtStartOfDay).toList
    log.info("Found %d deploys to summarise" format deploys.size)
    deploys.foreach(detail => store.summariseDeploy(detail.uuid))
    log.info("Finished summarising")
    deploys.size
  }

  var summariseSchedule: Option[ScheduledAgent[Int]] = None

  val update = DailyScheduledAgentUpdate[Int](housekeepingTime) { _ + summariseDeploys() }

  def init() {
    summariseSchedule = Some(ScheduledAgent(0, update))
  }
  def shutdown() {
    summariseSchedule.foreach(_.shutdown())
    summariseSchedule = None
  }
}
