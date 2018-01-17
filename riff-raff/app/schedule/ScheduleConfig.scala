package schedule

import java.util.UUID

import controllers.ScheduleController.Schedule
import org.joda.time.DateTime

case class ScheduleConfig(id: UUID, projectName: String, stage: String, schedule: Schedule, enabled: Boolean, lastEdited: DateTime, user: String)