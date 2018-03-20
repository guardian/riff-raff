package schedule

import java.util.UUID

import org.joda.time.DateTime

case class ScheduleConfig(id: UUID, projectName: String, stage: String,
                          scheduleExpression: String, timezone: String,
                          enabled: Boolean, lastEdited: DateTime, user: String,
                          cooldownDays: Option[Int])