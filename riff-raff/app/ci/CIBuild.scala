package ci

import java.util.concurrent.Executors

import conf.Configuration
import rx.lang.scala.Observable
import org.joda.time.DateTime
import controllers.Logging
import magenta.artifact.S3Object

import scala.concurrent.{ExecutionContext, Future}

trait CIBuild {
  def jobName: String
  def jobId: String
  def branchName: String
  def number: String
  def id: Long
  def startTime: DateTime
}

trait Job {
  def id: String
  def name: String
}

object CIBuild extends Logging {
  import concurrent.duration._
  import play.api.libs.concurrent.Execution.Implicits._

  implicit val ord = Ordering.by[CIBuild, Long](_.id)

  val pollingPeriod = Configuration.build.pollingPeriodSeconds.seconds

  lazy val jobs: Observable[Job] = builds.map(b => S3Project(b.jobId, b.jobName)).distinct.publish.refCount

  lazy val newBuilds: Observable[CIBuild] = {
    val observable = (for {
      location <- Every(pollingPeriod)(Observable.from(S3Build.buildJsons)).distinct if !initialFiles.contains(location)
      build <- Observable.from(retrieveBuild(location))
    } yield build).publish
    observable.connect // make it a "hot" observable, i.e. it runs even if nobody is subscribed to it
    observable
      .doOnError(e => log.error(s"Error polling for new builds", e))
      .doOnNext(b => log.info(s"Found $b"))
      .doOnTerminate(log.info("Terminated whilst waiting for new builds"))
  }

  lazy val initialFiles: Seq[S3Object] = for {
    location <- S3Build.buildJsons
  } yield location

  lazy val initialBuilds: Future[Seq[CIBuild]] = {
    implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(50))
    log.logger.info(s"Found ${initialFiles.length} builds to parse")
    (Future.traverse(initialFiles)(l => Future(retrieveBuild(l)))).map(_.flatten)
  }

  private def retrieveBuild(location: S3Object): Option[CIBuild] = {
    import cats.syntax.either._
    S3Build.buildAt(location)
      .leftMap(e => log.error(s"Problem getting build definition from $location: $e"))
      .toOption
  }

  lazy val builds: Observable[CIBuild] = Observable.from(initialBuilds).flatMap(Observable.from(_)).merge(newBuilds)
}
