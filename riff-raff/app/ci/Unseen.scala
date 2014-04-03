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