package ci

import conf.Configuration
import rx.lang.scala.Observable
import org.joda.time.DateTime
import ci.teamcity.Job
import controllers.Logging
import rx.lang.scala.schedulers.NewThreadScheduler

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
  lazy val jobs: Observable[Job] = builds.map(b => S3Project(b.jobId, b.jobName)).distinct.publish.refCount

  lazy val newBuilds: Observable[CIBuild] =
    (for {
      location <- (Every(10.seconds)(Observable.from(S3Build.buildJsons))).distinct if !initialFiles.contains(location)
      build <- Observable.from(S3Build.buildAt(location))
    } yield build).publish.refCount

  lazy val initialFiles: Seq[S3Location] = for {
    location <- S3Build.buildJsons
  } yield location

  lazy val initialBuilds: Seq[CIBuild] = {
    log.logger.info(s"${initialFiles.length}")
    (for {
      location <- initialFiles.par
      build <- S3Build.buildAt(location)
    } yield build).seq.view
  }

  lazy val builds: Observable[CIBuild] = Observable.from(initialBuilds).merge(newBuilds)
}
