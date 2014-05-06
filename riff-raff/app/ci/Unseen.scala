package ci

import rx.lang.scala.Observable
import scala.collection.immutable.Queue
import scala.util.Random
import scala.concurrent.duration._

object Unseen {
  def apply[T](obs: Observable[T]): Observable[T] = apply(Nil, obs)

  def apply[T](seed: Iterable[T], obs: Observable[T]): Observable[T] = {
    obs.scan((Option.empty[T], BoundedSet[T](1000) ++ seed)) {
      case ((_, seen), current) => (if (seen.contains(current)) None else Some(current), seen + current)
    } flatMap {
      case (opt, _) => Observable.from(opt)
    }
  }
}

object GreatestSoFar {
  def apply[T: Ordering](obs: Observable[T]): Observable[T] = {
    val ord = implicitly[Ordering[T]]
    obs.scan((prev, current) => if (ord.gt(current, prev)) current else prev)
  }
}

object AtSomePointIn {
  def apply[T](window: Duration)(act: => Observable[T]): Observable[T] = {
    val kickOffTime = Duration.create(Random.nextInt(window.toMillis.toInt), MILLISECONDS)
    Observable.interval(kickOffTime).first.flatMap(_ => act)
  }
}

class BoundedSet[T](queue: Queue[T], maxSize: Int) extends Set[T] {
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

  def -(elem: T) = new BoundedSet[T](queue.filter(_ != elem), maxSize)

  def iterator = queue.iterator
}

object BoundedSet {
  def apply[T](maxSize: Int) = new BoundedSet(Queue.empty[T], maxSize)
}