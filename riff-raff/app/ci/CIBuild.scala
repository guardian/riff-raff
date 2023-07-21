package ci

import java.util.concurrent.Executors

import conf.Config
import rx.lang.scala.Observable
import org.joda.time.DateTime
import controllers.Logging
import magenta.Build
import magenta.artifact.S3Object
import scala.collection.Seq

import scala.concurrent.{ExecutionContext, Future}

trait CIBuild {
  def jobName: String
  def jobId: String
  def branchName: String
  def number: String
  def id: Long
  def startTime: DateTime
  def vcsURL: String
  def buildTool: Option[String]
  def toMagentaBuild: Build = Build(jobName, number)
}

object CIBuild {
  implicit val ord: Ordering[CIBuild] =
    Ordering.by[CIBuild, DateTime](_.startTime)
}

trait Job {
  def id: String
  def name: String
}

class CIBuildPoller(
    config: Config,
    s3BuildOps: S3BuildOps,
    executionContext: ExecutionContext
) extends Logging {
  import concurrent.duration._
  implicit val ec = executionContext

  val pollingPeriod = config.build.pollingPeriodSeconds.seconds

  val initialFiles: Seq[S3Object] = for {
    location <- s3BuildOps.buildJsons
  } yield location

  val initialBuilds: Future[Seq[CIBuild]] = {
    implicit val ec =
      ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(50))
    log.logger.info(s"Found ${initialFiles.length} builds to parse")
    Future.traverse(initialFiles)(l => Future(retrieveBuild(l))).map(_.flatten)
  }

  val newBuilds: Observable[CIBuild] = {
    val observable = (for {
      location <- Every(pollingPeriod)(
        Observable.from(s3BuildOps.buildJsons)
      ).distinct if !initialFiles.contains(location)
      build <- Observable.from(retrieveBuild(location))
    } yield build).publish
    observable.connect // make it a "hot" observable, i.e. it runs even if nobody is subscribed to it
    observable
      .doOnError(e => log.error(s"Error polling for new builds", e))
      .doOnNext(b => log.info(s"Found $b"))
      .doOnTerminate(log.info("Terminated whilst waiting for new builds"))
  }

  val builds: Observable[CIBuild] =
    Observable.from(initialBuilds).flatMap(Observable.from(_)).merge(newBuilds)

  val jobs: Observable[Job] =
    builds.map(b => S3Project(b.jobId, b.jobName)).distinct.publish.refCount

  private def retrieveBuild(location: S3Object): Option[CIBuild] = {
    import cats.syntax.either._
    s3BuildOps
      .buildAt(location)
      .leftMap(e =>
        log.error(s"Problem getting build definition from $location: $e")
      )
      .toOption
  }
}
