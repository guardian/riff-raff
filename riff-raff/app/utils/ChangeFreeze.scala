package utils

import org.joda.time.{Interval, DateTime}
import conf.Config
import org.joda.time.format.DateTimeFormat

class ChangeFreeze(config: Config) {
  lazy val stages = config.freeze.stages
  lazy val message = config.freeze.message
  lazy val freezeInterval =
    (config.freeze.startDate, config.freeze.endDate) match {
      case (Some(startDate:DateTime), Some(endDate:DateTime)) =>
        Some(new Interval(startDate, endDate))
      case _ => None
    }

  lazy val formatter = DateTimeFormat.longDateTime()
  lazy val from:String = config.freeze.startDate.map(formatter.print).getOrElse("")
  lazy val to:String = config.freeze.endDate.map(formatter.print).getOrElse("")

  def choose[A](defrosted: A)(frozen: A):A =
    if (freezeInterval.exists(_.contains(new DateTime()))) frozen else defrosted

  def chooseWithStage[A](forStage: String)(defrosted: A)(frozen: A):A =
    choose(defrosted) {
      if (stages.contains(forStage)) {
        frozen
      } else {
        defrosted
      }
    }

  def frozen(stage:String) = chooseWithStage(stage)(false)(true)
  def frozen = choose(false)(true)
}