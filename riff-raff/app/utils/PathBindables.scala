package utils

import magenta.Strategy
import play.api.mvc.QueryStringBindable

object PathBindables {
  implicit val strategyQueryBindable: QueryStringBindable[Strategy] = enumeratum.UrlBinders.queryBinder(Strategy)
}
