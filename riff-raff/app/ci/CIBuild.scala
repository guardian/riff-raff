package ci

import conf.Configuration
import rx.lang.scala.Observable
import org.joda.time.DateTime
import ci.teamcity.Job
import controllers.Logging

trait CIBuild {
  def jobName: String
  def jobId: String
  def branchName: String
  def number: String
  def id: Long
  def startTime: DateTime
}

object CIBuild extends Logging {
  import concurrent.duration._
  import play.api.libs.concurrent.Execution.Implicits._

  implicit val ord = Ordering.by[CIBuild, Long](_.id)

  val pollingPeriod = Configuration.teamcity.pollingPeriodSeconds.seconds
  val jobs: Observable[Job] = Every(pollingPeriod)(TeamCityAPI.jobs)
  val recentBuildJobIds: Observable[String] = Every(pollingPeriod)(TeamCityAPI.recentBuildJobIds)

  def newBuilds(job: Job): Observable[CIBuild] = (for {
    id <- recentBuildJobIds if id == job.id
    builds <- {
      log.debug(s"Recent build for job $job / $id")
      AtSomePointIn(pollingPeriod)(TeamCityAPI.builds(job)).onErrorResumeNext { e =>
        log.error(s"Failed tor retrieve builds for job $job", e)
        Observable.empty
      }
    }
  } yield builds).publish.refCount

  val builds = for {
    job <- jobs.distinct
    build <- (TeamCityAPI.builds(job) merge newBuilds(job)).distinct
  } yield build
}
