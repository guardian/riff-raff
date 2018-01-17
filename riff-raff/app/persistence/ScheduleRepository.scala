package persistence

import java.util.UUID

import ci.Trigger
import com.gu.scanamo.syntax._
import com.gu.scanamo.{DynamoFormat, Table}
import schedule.ScheduleConfig

object ScheduleRepository extends DynamoRepository {

  implicit val triggerModeFormat =
    DynamoFormat.coercedXmap[Trigger.Mode, String, NoSuchElementException](Trigger.withName)(_.toString)

  override val tablePrefix = "schedule-config"

  val table = Table[ScheduleConfig](tableName)

  def getScheduleList(): List[ScheduleConfig] =
    exec(table.scan()).flatMap(_.toOption)

  def getSchedule(id: UUID): Option[ScheduleConfig] =
    exec(table.get('id -> id)).flatMap(_.toOption)

  def setSchedule(schedule: ScheduleConfig): Unit =
    exec(table.put(schedule))

  def deleteSchedule(id: UUID): Unit =
    exec(table.delete('id -> id))
}
