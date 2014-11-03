package ci

import controllers.Logging
import scala.concurrent.{ExecutionContext, Future}
import rx.lang.scala.Observable
import ci.teamcity._
import ci.teamcity.TeamCity.{BuildLocator, BuildTypeLocator}
import concurrent.duration._
import conf.{Configuration, TeamCityMetrics}
import org.joda.time.DateTime

object Every {

  def apply[T](frequency: Duration)
              (buildRetriever: => Observable[T])
              (implicit ec: ExecutionContext): Observable[T] = {
    (for {
      _ <- Observable.timer(1.second, frequency)
      builds <- buildRetriever
    } yield builds).publish.refCount
    // publish.refCount turns this from a 'cold' to a 'hot' observable (http://www.introtorx.com/content/v1.0.10621.0/14_HotAndColdObservables.html)
    // i.e. however many subscriptions, we only make one set of API calls
  }
}

trait ContinuousIntegrationAPI {
  def jobs(implicit ec: ExecutionContext): Observable[Job]
  def builds(job: Job)(implicit ec: ExecutionContext): Observable[CIBuild]
  def succesfulBuildBatch(job: Job)(implicit ec: ExecutionContext): Observable[Iterable[CIBuild]]
  def latest(build: CIBuild)(implicit ec: ExecutionContext): Future[Option[CIBuild]]
}

object FailSafeObservable extends Logging {
  def apply[T](f: Future[T], msg: => String)(implicit ec: ExecutionContext): Observable[T] =
    Observable.from(f).onErrorResumeNext { e =>
      log.error(msg, e)
      Observable.empty
    }
}

object TeamCityAPI extends ContinuousIntegrationAPI with Logging {
  def jobs(implicit ec: ExecutionContext): Observable[Job] =
    FailSafeObservable(BuildTypeLocator.list, "Couldn't retrieve build types").flatMap(Observable.from(_))

  def builds(job: Job)(implicit ec: ExecutionContext): Observable[CIBuild] = for {
    builds <- TeamCityAPI.succesfulBuildBatch(job)
    build <- Observable.from(builds)
  } yield build

  def succesfulBuildBatch(job: Job)(implicit ec: ExecutionContext): Observable[Iterable[CIBuild]] = {
    FailSafeObservable({
      val startTime = DateTime.now()
      TeamCityWS.url(s"/app/rest/builds?locator=status:SUCCESS,buildType:${job.id},branch:default:any&count=20&fields=build(id,number,status,startDate,branchName,buildTypeId,webUrl,tags)").get().flatMap { r =>
        TeamCityMetrics.ApiCallTimer.recordTimeSpent(DateTime.now.getMillis - startTime.getMillis)
        BuildSummary(r.xml, (id: String) => Future.successful(Some(job)), false)
      }
    }, s"Couldn't find batch for $job")
  }

  def recentBuildJobIds(implicit ec: ExecutionContext): Observable[String] = {
    FailSafeObservable({
      TeamCity.api.build.since(DateTime.now.minusMinutes(Configuration.teamcity.pollingWindowMinutes)).get().map { r =>
        (r.xml \\ "@buildTypeId").map(_.text).distinct
      }
    }, "Couldn't find recent build job ids") flatMap (Observable.from(_))
  }

  def latest(build: CIBuild)(implicit ec: ExecutionContext): Future[Option[CIBuild]] = {
    build match {
      case b:TeamcityBuild =>
        TeamCityWS.url(s"/app/rest/builds?locator=buildType:${b.jobId},number:${b.number},branch:default:any&fields=build(id,number,status,startDate,branchName,buildTypeId,webUrl,tags)").get().flatMap { r =>
          BuildSummary(r.xml, (id: String) => Future.successful(Some(b.job)), false).map(_.headOption)
        }
    }
  }
}

