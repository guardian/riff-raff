package ci

import conf.Configuration
import rx.lang.scala.Observable
import org.joda.time.DateTime
import ci.teamcity.Job

trait CIBuild {
  def jobName: String
  def jobId: String
  def branchName: String
  def number: String
  def id: Long
  def startTime: DateTime
}

object CIBuild {
  import concurrent.duration._
  import play.api.libs.concurrent.Execution.Implicits._

  implicit val ord = Ordering.by[CIBuild, Long](_.id)

  val pollingPeriod = Configuration.teamcity.pollingPeriodSeconds.seconds
  val jobs: Observable[Job] = Every(pollingPeriod)(TeamCityAPI.jobs)
  val buildBatchesForAllJobs: Observable[Observable[Iterable[CIBuild]]] = for {
    job <- Unseen(CIBuild.jobs)
  } yield
    Unseen.iterable(
      AtSomePointIn(pollingPeriod)(
        Every(pollingPeriod)(
          TeamCityAPI.builds(job)
        )
      )
    )

  val builds = for {
    buildBatchesForJob <- buildBatchesForAllJobs
    buildBatch <- buildBatchesForJob
    build <- Observable.from(buildBatch)
  } yield build
}
