package utils

import play.api.mvc.{EssentialAction, EssentialFilter, RequestHeader}

import scala.concurrent.ExecutionContext

class HstsFilter(implicit val executionContext: ExecutionContext)
    extends EssentialFilter {
  def apply(next: EssentialAction) = new EssentialAction {
    def apply(request: RequestHeader) = next(request).map(
      _.withHeaders("Strict-Transport-Security" -> "max-age=31536000")
    )
  }
}
