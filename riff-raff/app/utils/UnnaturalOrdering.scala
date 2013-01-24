package utils

/**
 * Class to help ordering things in a mandated order, rather than the natural ordering of a given type
 * @param order The seq of objects in the order they should be returned in
 * @param aliensAtEnd If true, the compare function will sort unknown items to the end, false will sort them to the start
 * @tparam A
 */
class UnnaturalOrdering[A](val order: List[A], val aliensAtEnd: Boolean = true, val oldOrdering: Ordering[A]) extends Ordering[A] {
  val alienValue = if (aliensAtEnd) order.size+1 else -1
  val orderMap = order.zipWithIndex.map(e => e._1 -> e._2).toMap.withDefault(_ => alienValue)
  def compare(left: A, right: A): Int = {
    val leftIndex = orderMap(left)
    val rightIndex = orderMap(right)
    if (leftIndex == alienValue && rightIndex == alienValue)
      oldOrdering.compare(left, right)
    else
      leftIndex compare rightIndex
  }
}

object UnnaturalOrdering {
  def apply[A](order: List[A], aliensAtEnd: Boolean = true)(implicit oldOrdering: Ordering[A]): UnnaturalOrdering[A] = {
    new UnnaturalOrdering[A](order, aliensAtEnd, oldOrdering)
  }
}
