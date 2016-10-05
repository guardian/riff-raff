package utils

import play.api.mvc.{RequestHeader, EssentialAction, EssentialFilter}
import play.api.libs.concurrent.Execution.Implicits._

class HstsFilter extends EssentialFilter {
  def apply(next: EssentialAction) = new EssentialAction {
    def apply(request: RequestHeader) =
      next(request).map(_.withHeaders("Strict-Transport-Security" -> "max-age=31536000"))
  }
}
