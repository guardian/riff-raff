package ci

import scala.collection.immutable.Queue

class BoundedSet[T](queue: Queue[T], maxSize: Int) extends Set[T] {
  override def incl(elem: T): BoundedSet[T] = {
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
    (queue.slice(0, currentPos) ++ queue.slice(currentPos + 1, queue.size))
      .enqueue(item)
  }

  override def excl(elem: T) =
    new BoundedSet[T](queue.filter(_ != elem), maxSize)

  def iterator = queue.iterator
}

object BoundedSet {
  def apply[T](maxSize: Int) = new BoundedSet(Queue.empty[T], maxSize)
}
