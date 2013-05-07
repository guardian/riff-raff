package utils

import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers
import org.joda.time.DateMidnight

class GraphTest extends FunSuite with ShouldMatchers {
  test("should add zeros for missing days") {
    val statsWithMissingDays = List(
      new DateMidnight(2013, 5, 1) -> 4,
      new DateMidnight(2013, 5, 3) -> 2
    )

    Graph.zeroFillDays(statsWithMissingDays) should be (List(
      new DateMidnight(2013, 5, 1) -> 4,
      new DateMidnight(2013, 5, 2) -> 0,
      new DateMidnight(2013, 5, 3) -> 2
    ))
  }

}
