package utils

import org.joda.time.{Interval, DateTime}
import conf.Configuration.freeze
import org.joda.time.format.DateTimeFormat

object ChangeFreeze {
  lazy val stages = freeze.stages
  lazy val message = freeze.message
  lazy val freezeInterval =
    (freeze.startDate, freeze.endDate) match {
      case (Some(startDate: DateTime), Some(endDate: DateTime)) =>
        Some(new Interval(startDate, endDate))
      case _ => None
    }

  lazy val formatter = DateTimeFormat.longDateTime()
  lazy val from: String = freeze.startDate.map(formatter.print).getOrElse("")
  lazy val to: String = freeze.endDate.map(formatter.print).getOrElse("")

  def choose[A](defrosted: A)(frozen: A): A =
    if (freezeInterval.exists(_.contains(new DateTime()))) frozen else defrosted

  def chooseWithStage[A](forStage: String)(defrosted: A)(frozen: A): A =
    choose(defrosted) {
      if (stages.contains(forStage)) {
        frozen
      } else {
        defrosted
      }
    }

  def frozen(stage: String) = chooseWithStage(stage)(false)(true)
  def frozen = choose(false)(true)
}
