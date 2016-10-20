package deployment.preview

import org.joda.time.DateTime

import scala.concurrent.Future

case class PreviewResult(future: Future[Preview], startTime: DateTime = new DateTime()) {
  def duration = new org.joda.time.Duration(startTime, new DateTime())
}