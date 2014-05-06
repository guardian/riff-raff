package ci

import org.scalatest.FunSuite
import rx.lang.scala.Observable
import org.scalatest.matchers.ShouldMatchers

class UnseenTest extends FunSuite with ShouldMatchers {
  test("only emit unseen values") {
    val unseen = Unseen(Observable.items(0, 1, 2, 0, 1, 3))
    unseen.toBlockingObservable.toList should be(List(0, 1, 2, 3))
  }

  test("should not provide elements provided in seed") {
    val unseen = Unseen(Seq(1), Observable.items(0, 1, 2, 0, 1, 3))
    unseen.toBlockingObservable.toList should be(List(0, 2, 3))
  }

  test("should return the greatest element seen so far") {
    GreatestSoFar(Observable.items(1, 2, 3, 3, 5, 4)).toBlockingObservable.toList should be (
      List(1, 2, 3, 3, 5, 5))
  }

  test("bounded set obeys contains") {
    val set = BoundedSet[Int](5) + 1 + 2
    Seq(1,2) map (i => set.contains(i) should be (true))
    set.contains(3) should be (false)
  }

  test("bounded set should dump first element if overflows") {
    val set = BoundedSet[Int](5) + 1 + 2 + 3 + 4 + 5 + 6

    Seq(2,3,4,5,6) map (i => set.contains(i) should be (true))
    set.contains(1) should be (false)
  }

  test("bounded set's capacity isn't affected by duplicates") {
    val set = BoundedSet[Int](5) + 1 + 2 + 2 + 2 + 2 + 3

    Seq(1,2,3) map (i => set.contains(i) should be (true))
  }

  test("bounded set should keep the most recent regardless of dupes")  {
    val set = BoundedSet[Int](2) + 1 + 2 + 2 + 2 + 1 + 3

    Seq(1,3) map (i => set.contains(i) should be (true))
  }
}
