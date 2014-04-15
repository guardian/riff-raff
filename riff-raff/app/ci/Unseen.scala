package ci

import rx.lang.scala.Observable
import scala.collection.immutable.Queue

object Unseen {
  def apply[T](obs: Observable[Iterable[T]]): Observable[Iterable[T]] = {
    obs.scan((Seq[T]().toIterable, BoundedSet[T](10000))) {
      case ((_, seen), current) => (current.filterNot(seen.contains), current.foldLeft(seen)(_ + _))
    } map {
      case (elems, _) => elems
    } drop (1)
  }
}

object NotFirstBatch {
  def apply[T](obs: Observable[Iterable[T]]): Observable[Iterable[T]] = {
    obs.dropWhile(_.toSeq.isEmpty).drop(1)
  }
}

object Latest {
  def by[T: Ordering, K](obs: Observable[T])(groupBy: T => K): Observable[T] = {
    val ord = implicitly[Ordering[T]]
    obs.groupBy(groupBy).flatMap { case (_, o) =>
      o.scan(Option.empty[T], Option.empty[T]) {
        case ((_, maxed), current) => {
          if (maxed.exists(ord.gt(_, current)))
            (None, maxed)
          else
            (Some(current), Some(current))
        }
      } map {
        case (elems, _) => elems
      } flatMap (Observable.from(_))
    }
  }
}

class BoundedSet[T](queue: Queue[T], maxSize: Int) {
  def +(elem: T): BoundedSet[T] = {
    if (contains(elem)) {
      new BoundedSet(pushToBack(queue, elem), maxSize)
    } else {
      if (queue.size < maxSize) new BoundedSet[T](queue.enqueue(elem), maxSize)
      else new BoundedSet(queue.dequeue._2.enqueue(elem), maxSize)
    }
  }
  def contains(elem: T): Boolean = queue.toSet.contains(elem)

  def pushToBack(queue: Queue[T], item: T): Queue[T] = {
    val currentPos = queue.indexOf(item)
    (queue.slice(0, currentPos) ++ queue.slice(currentPos + 1, queue.size)).enqueue(item)
  }

}

object BoundedSet {
  def apply[T](maxSize: Int) = new BoundedSet(Queue.empty[T], maxSize)
}