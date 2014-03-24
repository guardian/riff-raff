package ci

import org.scalatest.FunSuite
import rx.lang.scala.Observable
import org.scalatest.matchers.ShouldMatchers
import scala.concurrent.ExecutionContext
import concurrent.duration._

class UnseenTest extends FunSuite with ShouldMatchers {
  import ExecutionContext.Implicits.global
  test("should only provide unseen elements") {
    val unseen = Unseen(Observable.interval(100.millis).take(5).map(_ % 3).map(Seq(_)))
    unseen.toBlockingObservable.toList should be(List(Seq(0),Seq(1),Seq(2), Seq(), Seq()))
  }

  test("should not pass on anything if nothing seen previously") {
    val notFirstBatch = NotFirstBatch(Observable.timer(0.millis, 10.millis).take(5).map(Seq(_)))
    notFirstBatch.toBlockingObservable.toList should be(List(Seq(1),Seq(2), Seq(3), Seq(4)))
  }

  test("should ignore multiple empty elements") {
    val stuff = Seq(Seq(), Seq(), Seq(1,2), Seq(2,3,4), Seq(2,3,4))
    val notFirstBatch = NotFirstBatch(Observable.interval(10.millis).take(5).map(_.toInt).map(stuff(_)))
    notFirstBatch.toBlockingObservable.toList should be(List(Seq(2,3,4), Seq(2,3,4)))
  }

  test("can combine unseen with not first batch") {
    val stuff = Seq(Seq(), Seq(), Seq(1,2), Seq(2,3,4), Seq(2,3,4), Seq(2,3,4,5))
    val notFirstUnseen = NotFirstBatch(Unseen(Observable.timer(0.millis, 100.millis).take(6).map(_.toInt).map(stuff(_))))
    notFirstUnseen.toBlockingObservable.toList should be(List(Seq(3,4), Seq(), Seq(5)))
  }
}
