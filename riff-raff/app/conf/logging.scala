package conf

import play.api.libs.concurrent.Execution.Implicits._
import _root_.play.api.mvc._
import concurrent.Future
import util.Try
import com.gu.management._
import util.Failure
import util.Success
import controllers.Logging

object RiffRaffRequestMeasurementMetrics extends RiffRaffRequestMetrics.Standard

object RiffRaffRequestMetrics {

  trait Standard {
    val knownResultTypeCounters = List(OkCounter(), RedirectCounter(), NotFoundCounter(), ErrorCounter())

    val otherCounter = OtherCounter(knownResultTypeCounters)

    val asFilters: List[MetricsFilter] = List(LoggingTimingFilter(), CountersFilter(otherCounter :: knownResultTypeCounters))

    val asMetrics: List[Metric] = asFilters.flatMap(_.metrics).distinct
  }

  trait MetricsFilter extends Filter {
    val metrics: Seq[Metric]
  }

  object LoggingTimingFilter extends Logging {
    def apply(): MetricsFilter = {
      val timingMetric = new TimingMetric("performance", "request_duration", "Client requests", "incoming requests to the application")

      new MetricsFilter {
        val metrics = Seq(timingMetric)

        def apply(next: (RequestHeader) => Result)(rh: RequestHeader): Result = {
          val s = new StopWatch
          val result = next(rh)
          onFinalPlainResult(result) { _ =>
            timingMetric.recordTimeSpent(s.elapsed)
            log.info(s"Request completed for ${rh.method} ${rh.uri} in ${s.elapsed}ms")
          }
          result
        }
      }
    }
  }

  object CountersFilter {
    def apply(counters: List[Counter]): MetricsFilter = new MetricsFilter {
      val metrics = counters.map(_.countMetric)

      def apply(next: (RequestHeader) => Result)(rh: RequestHeader): Result = {
        val result = next(rh)
        onFinalPlainResult(result) {
          plainResultTry => counters.foreach(_.submit(plainResultTry))
        }
        result
      }
    }
  }

  case class Counter(condition: Try[PlainResult] => Boolean, countMetric: CountMetric) {
    def submit(resultTry: Try[PlainResult]) {
      if (condition(resultTry)) countMetric increment ()
    }
  }

  object OkCounter {
    def apply() = Counter(StatusCode(200), new CountMetric("request-status", "200_ok", "200 Ok", "number of pages that responded 200"))
  }

  object RedirectCounter {
    def apply() = Counter(StatusCode(301, 302), new CountMetric("request-status", "30x_redirect", "30x Redirect", "number of pages that responded with a redirect"))
  }

  object NotFoundCounter {
    def apply() = Counter(StatusCode(404), new CountMetric("request-status", "404_not_found", "404 Not found", "number of pages that responded 404"))
  }

  object ErrorCounter {
    def apply() = Counter(t => { t.isFailure || (StatusCode(500 to 509)(t)) }, new CountMetric("request-status", "50x_error", "50x Error", "number of pages that responded 50x"))
  }

  object OtherCounter {
    def apply(knownResultTypeCounters: Seq[Counter]) = {
      def unknown(result: Try[PlainResult]) = !knownResultTypeCounters.exists(_.condition(result))

      Counter(unknown, new CountMetric("request-status", "other", "Other", "number of pages that responded with an unexpected status code"))
    }
  }

  object StatusCode {

    def apply(codes: Traversable[Int]): Try[PlainResult] => Boolean = apply(codes.toSet: Int => Boolean)

    def apply(codes: Int*): Try[PlainResult] => Boolean = apply(Set(codes: _*): Int => Boolean)

    def apply(condition: Int => Boolean)(resultTry: Try[PlainResult]) =
      resultTry.map(plainResult => condition(plainResult.header.status)).getOrElse(false)
  }

  /**
   * AsyncResult can - theoretically- return another AsyncResult, which we don't want. Redeem only when have a
   * non-async result.
   */
  private def onFinalPlainResult(result: Result)(k: Try[PlainResult] => Unit) {
    result match {
      case plainResult: PlainResult => k(Success(plainResult))
      case AsyncResult(future: Future[Result]) => future onComplete {
        case Success(anotherAsyncResult) => onFinalPlainResult(anotherAsyncResult)(k)
        case Failure(e) => k(Failure(e))
      }
    }
  }

}

