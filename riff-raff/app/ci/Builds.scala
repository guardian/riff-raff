package ci

import controllers.Logging
import scala.concurrent.{ExecutionContext, Future}
import rx.lang.scala.Observable
import conf.Configuration
import ci.teamcity.{BuildSummary, TeamCityWS}
import ci.teamcity.TeamCity.BuildTypeLocator
import play.api.libs.ws.WS
import play.api.libs.json.JsArray

object Builds extends Logging {
  import concurrent.duration._

  def every[T](frequency: concurrent.duration.Duration)(buildRetriever: => Future[Iterable[T]])(implicit ec: ExecutionContext): Observable[Iterable[T]] = {
    (for {
      _ <- Observable.timer(5.seconds, frequency)
      builds <- Observable.from(buildRetriever.recover { case e =>
        log.logger.error("Error while polling", e)
        Seq()
      })
    } yield builds).publish.refCount
    // publish.refCount turns this from a 'cold' to a 'hot' observable (http://www.introtorx.com/content/v1.0.10621.0/14_HotAndColdObservables.html)
    // i.e. however many subscriptions, we only make one set of API calls
  }

  def teamcity(implicit ec: ExecutionContext): Observable[Iterable[CIBuild]] = every(Configuration.teamcity.pollingPeriodSeconds.seconds)(
    TeamCityWS.url("/app/rest/builds").get().flatMap { r =>
      BuildSummary(r.xml, (id: String) => {
        BuildTypeLocator.list.map(_.find(_.id == id))
      }, false).map(_.groupBy(b => (b.buildType.fullName, b.branchName, b.status)).map({case (_, builds) =>
        builds.maxBy(_.id)
      }))
    }
  )
}

