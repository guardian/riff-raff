package utils

import org.joda.time.DateMidnight

object Graph {
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
