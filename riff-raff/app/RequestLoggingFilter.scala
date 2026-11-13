import controllers.Logging
import net.logstash.logback.marker.Markers.appendEntries
import org.apache.pekko.stream.Materializer
import play.api.MarkerContext
import play.api.mvc.{Filter, RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

class RequestLoggingFilter(override val mat: Materializer)(implicit
    ec: ExecutionContext
) extends Filter
    with Logging {
  override def apply(
      next: (RequestHeader) => Future[Result]
  )(rh: RequestHeader): Future[Result] = {
    val start = System.currentTimeMillis()
    val result = next(rh)

    result onComplete {
      case Success(response) =>
        val duration = System.currentTimeMillis() - start
        logSuccess(rh, response, duration)

      case Failure(err) =>
        val duration = System.currentTimeMillis() - start
        logFailure(rh, err, duration)
    }

    result
  }

  private def logSuccess(
      request: RequestHeader,
      response: Result,
      duration: Long
  ): Unit = {
    val originIp =
      request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    val referer = request.headers.get("Referer").getOrElse("")
    val userAgent = request.headers.get("User-Agent").getOrElse("")
    val length = response.header.headers.getOrElse("Content-Length", 0)

    val mandatoryMarkers = Map(
      "origin" -> originIp,
      "referrer" -> referer,
      "method" -> request.method,
      "status" -> response.header.status,
      "duration" -> duration,
      "requestUri" -> request.uri,
      "userAgent" -> userAgent
    )

    val markers = MarkerContext(appendEntries(mandatoryMarkers.asJava))
    log.info(
      s"""$originIp - "${request.method} ${request.uri} ${request.version}" ${response.header.status} $length "$referer" ${duration}ms"""
    )(markers)
  }

  private def logFailure(
      request: RequestHeader,
      throwable: Throwable,
      duration: Long
  ): Unit = {
    val originIp =
      request.headers.get("X-Forwarded-For").getOrElse(request.remoteAddress)
    val referer = request.headers.get("Referer").getOrElse("")
    val userAgent = request.headers.get("User-Agent").getOrElse("")

    val mandatoryMarkers = Map(
      "origin" -> originIp,
      "referrer" -> referer,
      "method" -> request.method,
      "duration" -> duration,
      "requestUri" -> request.uri,
      "userAgent" -> userAgent
    )

    val markers = MarkerContext(appendEntries(mandatoryMarkers.asJava))
    log.info(
      s"""$originIp - "${request.method} ${request.uri} ${request.version}" ERROR "$referer" ${duration}ms"""
    )(markers)
    log.error(s"Error for ${request.method} ${request.uri}", throwable)
  }
}
