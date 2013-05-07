package utils

import org.joda.time.DateMidnight

object Graph {
  implicit def series2prefixDate(series: List[(DateMidnight, Int)]) = new {
    def prefixDate(prefixDate: Option[DateMidnight]):List[(DateMidnight, Int)] = series match {
      case Nil => Nil
      case (date, count) :: tail if prefixDate.isDefined && date != prefixDate.get =>
        (prefixDate.get, 0) :: (date, count) :: tail
      case list => list
    }
  }

  def zeroFillDays(deploysPerDay: List[(DateMidnight, Int)],
                   firstDate: Option[DateMidnight],
                   lastDate: Option[DateMidnight]): List[(DateMidnight, Int)] = {

    zeroFillDays(deploysPerDay match {
      case Nil => Nil
      case list => list.reverse.prefixDate(lastDate).reverse.prefixDate(firstDate)
    })
  }

  def zeroFillDays(deploysPerDay: List[(DateMidnight, Int)]): List[(DateMidnight, Int)] = deploysPerDay match {
    case Nil => Nil
    case head :: Nil => head :: Nil
    case (date1, count1) :: (date2, count2) :: tail => {
      val nextDay = date1.plusDays(1)
      if (date2 == nextDay)
        (date1, count1) :: zeroFillDays((date2, count2) :: tail)
      else
        (date1, count1) :: zeroFillDays((nextDay, 0) :: (date2, count2) :: tail)
    }
  }
}
