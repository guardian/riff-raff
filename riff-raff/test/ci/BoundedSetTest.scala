package ci

import org.scalatest.{FunSuite, Matchers}

class BoundedSetTest extends FunSuite with Matchers {
  test("bounded set obeys contains") {
    val set = BoundedSet[Int](5) + 1 + 2
    Seq(1, 2) map (i => set.contains(i) should be(true))
    set.contains(3) should be(false)
  }

  test("bounded set should dump first element if overflows") {
    val set = BoundedSet[Int](5) + 1 + 2 + 3 + 4 + 5 + 6

    Seq(2, 3, 4, 5, 6) map (i => set.contains(i) should be(true))
    set.contains(1) should be(false)
  }

  test("bounded set's capacity isn't affected by duplicates") {
    val set = BoundedSet[Int](5) + 1 + 2 + 2 + 2 + 2 + 3

    Seq(1, 2, 3) map (i => set.contains(i) should be(true))
  }

  test("bounded set should keep the most recent regardless of dupes") {
    val set = BoundedSet[Int](2) + 1 + 2 + 2 + 2 + 1 + 3

    Seq(1, 3) map (i => set.contains(i) should be(true))
  }
}
