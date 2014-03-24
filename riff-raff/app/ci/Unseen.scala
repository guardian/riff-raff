package ci

import rx.lang.scala.Observable
import akka.agent.Agent
import scala.concurrent.ExecutionContext

object Unseen {
  def apply[T](obs: Observable[Iterable[T]])(implicit ec: ExecutionContext): Observable[Iterable[T]] = {
    val seen = Agent(Set[T]())

    val fresh = obs.map(i => i.filter(!seen().contains(_)))

    Observable[Iterable[T]] { s =>
      s.add(fresh.subscribe(s.onNext(_), s.onError(_), () => s.onCompleted()))
      s.add(fresh.subscribe(i => i.foreach(e => seen.alter(_ + e))))
    }
  }
}

object NotFirstBatch {
  def apply[T](obs: Observable[Iterable[T]]): Observable[Iterable[T]] = {
    obs.dropWhile(_.toSeq.isEmpty).drop(1)
  }
}