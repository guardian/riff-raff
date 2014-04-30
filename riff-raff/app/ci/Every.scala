package ci

import controllers.Logging
import scala.concurrent.{ExecutionContext, Future}
import rx.lang.scala.Observable
import ci.teamcity.{Job, BuildSummary, TeamCityWS}
import ci.teamcity.TeamCity.BuildTypeLocator
import concurrent.duration._

object Every extends Logging {

  def apply[T](frequency: Duration)
              (buildRetriever: => Observable[T])
              (implicit ec: ExecutionContext): Observable[T] = {
    (for {
      _ <- Observable.timer(1.second, frequency)
      builds <- buildRetriever.onErrorResumeNext(Observable.empty)
    } yield builds).publish.refCount
    // publish.refCount turns this from a 'cold' to a 'hot' observable (http://www.introtorx.com/content/v1.0.10621.0/14_HotAndColdObservables.html)
    // i.e. however many subscriptions, we only make one set of API calls
  }
}

trait ContinuousIntegrationAPI {
  def jobs(implicit ec: ExecutionContext): Observable[Job]
  def builds(job: Job)(implicit ec: ExecutionContext): Observable[Iterable[CIBuild]]
}

object TeamCityAPI extends ContinuousIntegrationAPI {
  def jobs(implicit ec: ExecutionContext): Observable[Job] =
    Observable.from(BuildTypeLocator.list).flatMap(Observable.from(_))

  def builds(job: Job)(implicit ec: ExecutionContext): Observable[Iterable[CIBuild]] = {
    Observable.from(TeamCityWS.url(s"/app/rest/builds?locator=buildType:${job.id},branch:default:any&count=20").get().flatMap { r =>
      BuildSummary(r.xml, (id: String) => Future.successful(Some(job)), false)
    })
  }
}

