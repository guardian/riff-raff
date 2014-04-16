package ci

import conf.Configuration
import rx.lang.scala.Observable
import org.joda.time.DateTime

trait CIBuild {
  def projectName: String
  def projectId: String
  def branchName: String
  def number: String
  def id: Long
  def startTime: DateTime
}

object CIBuild {
  import concurrent.duration._
  import play.api.libs.concurrent.Execution.Implicits._

  implicit val ord = Ordering.by[CIBuild, Long](_.id)

  val teamCity = Every(Configuration.teamcity.pollingPeriodSeconds.seconds)(BuildRetrievers.teamcity)
  val teamCityBuilds = for {
    builds <- CIBuild.teamCity
    build <- Observable.from(builds)
  } yield build
}
