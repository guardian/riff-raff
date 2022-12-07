package ci

import rx.lang.scala.Observable
import scala.util.Random
import scala.concurrent.duration._

object GreatestSoFar {
  def apply[T: Ordering](obs: Observable[T]): Observable[T] = {
    val ord = implicitly[Ordering[T]]
    obs.scan((prev, current) => if (ord.gt(current, prev)) current else prev)
  }
}

object AtSomePointIn {
  def apply[T](window: Duration)(act: => Observable[T]): Observable[T] = {
    val kickOffTime =
      Duration.create(Random.nextInt(window.toMillis.toInt) + 1, MILLISECONDS)
    Observable.interval(kickOffTime).first.flatMap(_ => act)
  }
}
