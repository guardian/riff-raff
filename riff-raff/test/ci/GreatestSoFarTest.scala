package ci

import org.scalatest.{FunSuite, Matchers}
import rx.lang.scala.Observable

class GreatestSoFarTest extends FunSuite with Matchers {

  test("should return the greatest element seen so far") {
    GreatestSoFar(Observable.just(1, 2, 3, 3, 5, 4)).toBlocking.toList should be (
      List(1, 2, 3, 3, 5, 5))
  }
}
