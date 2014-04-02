package ci

import rx.lang.scala.Observable
import akka.agent.Agent
import scala.concurrent.ExecutionContext
import scala.collection.immutable.Queue

object Unseen {
  def apply[T](obs: Observable[Iterable[T]])(implicit ec: ExecutionContext): Observable[Iterable[T]] = {
    val seen = Agent(BoundedSet[T](10000))

    val fresh = obs.map(i => i.filter(!seen().contains(_)))

    Observable[Iterable[T]] { s =>
      s.add(fresh.subscribe(s.onNext, s.onError, () => s.onCompleted()))
      s.add(fresh.subscribe(i => i.foreach(e => seen.alter(_ + e))))
    }
  }
}

object NotFirstBatch {
  def apply[T](obs: Observable[Iterable[T]]): Observable[Iterable[T]] = {
    obs.dropWhile(_.toSeq.isEmpty).drop(1)
  }
}

class BoundedSet[T](queue: Queue[T], maxSize: Int) {
  def +(elem: T): BoundedSet[T] = {
    if (contains(elem)) {
      val currentPos = queue.indexOf(elem)
      new BoundedSet((queue.slice(0, currentPos) ++ queue.slice(currentPos + 1, queue.size)).enqueue(elem), maxSize)
    } else {
      if (queue.size < maxSize) new BoundedSet[T](queue.enqueue(elem), maxSize)
      else new BoundedSet(queue.dequeue._2.enqueue(elem), maxSize)
    }
  }
  def contains(elem: T): Boolean = queue.toSet.contains(elem)
}

object BoundedSet {
  def apply[T](maxSize: Int) = new BoundedSet(Queue.empty[T], maxSize)
}