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

trait Keyed[T, K] {
  def apply(t: T): K
}

object Latest {
  def apply[K, T](obs: Observable[T])(implicit ord: Ordering[T], key: Keyed[T,K]): Observable[T] = {
    obs.scan(Option.empty[T], BoundedMap[K, T](1000)) {
      case ((_, maxed), current) => {
        if (maxed.get(key(current)).exists(ord.gt(_, current)))
          (None, maxed)
        else
          (Some(current), maxed + (key(current) -> current))
      }
    } map {
      case (elems, _) => elems
    } flatMap (Observable.from(_))
  }
}

class BoundedSet[T](queue: Queue[T], maxSize: Int) {
  def +(elem: T): BoundedSet[T] = {
    if (contains(elem)) {
      val currentPos = queue.indexOf(elem)
      new BoundedSet(Bounded.pushToBack(queue, elem), maxSize)
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

class BoundedMap[K, V](map: Map[K, V], keys: Queue[K], maxSize: Int)  {
  def get(key: K) = map.get(key)
  def + (kv: (K, V)) = kv match { case (key, value) =>
    if (map.contains(key)) {
      val currentPos = keys.indexOf(key)
      val updatedKeys = Bounded.pushToBack(keys, key)
      new BoundedMap(map + kv, updatedKeys, maxSize)
    } else {
      if (keys.size < maxSize) {
        new BoundedMap(map + kv, keys.enqueue(key), maxSize)
      } else {
        val (keyToPurge, purgedKeys) = keys.dequeue
        new BoundedMap(map - keyToPurge + kv, purgedKeys.enqueue(key), maxSize)
      }
    }
  }
}
object BoundedMap {
  def apply[K, V](maxSize: Int) = new BoundedMap(Map.empty[K, V], Queue.empty[K], maxSize)
}

object Bounded {
  def pushToBack[T](queue: Queue[T], item: T): Queue[T] = {
    val currentPos = queue.indexOf(item)
    (queue.slice(0, currentPos) ++ queue.slice(currentPos + 1, queue.size)).enqueue(item)
  }
}