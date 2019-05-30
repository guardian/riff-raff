package persistence

import lifecycle.Lifecycle
import conf.Config
import org.joda.time.{LocalDate, LocalTime}
import utils.{DailyScheduledAgentUpdate, ScheduledAgent}
import controllers.Logging

class SummariseDeploysHousekeeping(config: Config, datastore: DataStore) extends Lifecycle with Logging {
  lazy val maxAgeDays = config.housekeeping.summariseDeploysAfterDays
  lazy val housekeepingTime = new LocalTime(config.housekeeping.hour, config.housekeeping.minute)

  def summariseDeploys(): Int = {
    log.info(s"Summarising deploys older than $maxAgeDays days")
    val maxAgeThreshold = LocalDate.now().minusDays(maxAgeDays)
    val deploys = datastore.getCompleteDeploysOlderThan(maxAgeThreshold.toDateTimeAtStartOfDay)
    log.info(s"Found ${deploys.size} deploys to summarise")
    deploys.foreach(detail => datastore.summariseDeploy(detail.uuid))
    log.info("Finished summarising")
    deploys.size
  }

  var summariseSchedule:Option[ScheduledAgent[Int]] = None

  val update = DailyScheduledAgentUpdate[Int](housekeepingTime){ _ + summariseDeploys() }

  def init() {
    summariseSchedule = Some(ScheduledAgent(0, update))
  }
  def shutdown() {
    summariseSchedule.foreach(_.shutdown())
    summariseSchedule = None
  }
}
