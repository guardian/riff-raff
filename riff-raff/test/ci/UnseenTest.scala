package ci

import org.scalatest.FunSuite
import rx.lang.scala.Observable
import org.scalatest.matchers.ShouldMatchers
import concurrent.duration._

class UnseenTest extends FunSuite with ShouldMatchers {
  test("only emit unseen values") {
    val unseen = Unseen(Observable.items(0, 1, 2, 0, 1, 3))
    unseen.toBlockingObservable.toList should be(List(0, 1, 2, 3))
  }

  test("should not provide elements provided in seed") {
    val unseen = Unseen(Seq(1), Observable.items(0, 1, 2, 0, 1, 3))
    unseen.toBlockingObservable.toList should be(List(0, 2, 3))
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

  test("latest can give latest item for some unique key on that item type") {
    case class Thing(name: String, v: Int)
    implicit val oThing = Ordering.by[Thing, Int](_.v)

    val things = Seq(Thing("a", 1), Thing("b", 2), Thing("b", 1), Thing("a", 3), Thing("a", 2))
    val latest = Latest.by(Observable.interval(10.millis).take(5).map(_.toInt).map(things(_)))(_.name)

    latest.toBlockingObservable.toList should be (List(Thing("a", 1), Thing("b", 2), Thing("a", 3)))
  }
}
