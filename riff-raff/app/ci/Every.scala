package ci

import controllers.Logging
import scala.concurrent.{ExecutionContext, Future}
import rx.lang.scala.Observable
import conf.Configuration
import ci.teamcity.{BuildType, BuildSummary, TeamCityWS}
import ci.teamcity.TeamCity.BuildTypeLocator
import play.api.libs.ws.WS
import play.api.libs.json.JsArray
import concurrent.duration._
import scala.util.Random

object Every extends Logging {

  def apply[T](frequency: Duration)
              (buildRetriever: (Duration) => Observable[Iterable[T]])
              (implicit ec: ExecutionContext): Observable[Iterable[T]] = {
    (for {
      _ <- Observable.timer(1.second, frequency)
      builds <- buildRetriever(frequency).onErrorResumeNext(Observable.empty)
    } yield builds).publish.refCount
    // publish.refCount turns this from a 'cold' to a 'hot' observable (http://www.introtorx.com/content/v1.0.10621.0/14_HotAndColdObservables.html)
    // i.e. however many subscriptions, we only make one set of API calls
  }
}

object BuildRetrievers {
  def teamcity(implicit ec: ExecutionContext): (Duration) => Observable[Iterable[CIBuild]] = (pollingWindow) =>
    Observable.from(BuildTypeLocator.list).flatMap { bts =>
      Observable.from(for (bt <- bts) yield {
        AtSomePointIn(pollingWindow)(buildsForType(bt))
      }).flatten
    }

  def buildsForType(buildType: BuildType)(implicit ec: ExecutionContext): Observable[Iterable[CIBuild]] = {
    Observable.from(TeamCityWS.url(s"/app/rest/builds?locator=buildType:${buildType.id},branch:default:any&count=20").get().flatMap { r =>
      BuildSummary(r.xml, (id: String) => Future.successful(Some(buildType)), false)
    })
  }
}

