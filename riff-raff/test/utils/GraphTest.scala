package utils

import org.scalatest.matchers.should.Matchers
import org.joda.time.LocalDate
import org.scalatest.funsuite.AnyFunSuite

class GraphTest extends AnyFunSuite with Matchers {
  test("should add zeros for missing days") {
    val statsWithMissingDays = List(
      new LocalDate(2013, 5, 1) -> 4,
      new LocalDate(2013, 5, 3) -> 2
    )

    Graph.zeroFillDays(statsWithMissingDays) should be(
      List(
        new LocalDate(2013, 5, 1) -> 4,
        new LocalDate(2013, 5, 2) -> 0,
        new LocalDate(2013, 5, 3) -> 2
      )
    )
  }

}
