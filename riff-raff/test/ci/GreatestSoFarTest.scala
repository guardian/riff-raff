package ci

import org.scalatest.FunSuite
import rx.lang.scala.Observable
import org.scalatest.matchers.ShouldMatchers

class GreatestSoFarTest extends FunSuite with ShouldMatchers {

  test("should return the greatest element seen so far") {
    GreatestSoFar(Observable.items(1, 2, 3, 3, 5, 4)).toBlockingObservable.toList should be (
      List(1, 2, 3, 3, 5, 5))
  }
}
