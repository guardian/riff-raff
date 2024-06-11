package persistence

import ci.Target
import org.scanamo.Table
import conf.Config
import org.joda.time.DateTime
import org.scanamo.generic.auto._

case class TargetId(
    targetKey: String,
    projectName: String,
    region: String,
    stack: String,
    app: String,
    lastSeen: DateTime
) {
  def matches(target: Target): Boolean =
    region == target.region && stack == target.stack && app == target.app
}
object TargetId {
  def apply(tgt: Target, projectName: String, lastSeen: DateTime): TargetId =
    TargetId(
      targetKey(tgt),
      projectName,
      tgt.region,
      tgt.stack,
      tgt.app,
      lastSeen
    )
  def targetKey(tgt: Target) = Seq(tgt.region, tgt.stack, tgt.app).mkString("|")
}

class TargetDynamoRepository(config: Config) extends DynamoRepository(config) {
  def tablePrefix = "riffraff-target-ids"
  val table = Table[TargetId](tableName)

  import org.scanamo.syntax._

  def set(
      target: Target,
      projectName: String,
      lastSeen: DateTime
  ): Unit =
    exec(table.put(TargetId(target, projectName, lastSeen)))

  def get(targetKey: String, projectName: String): Option[TargetId] = {
    exec(table.get("targetKey" -> targetKey and "projectName" -> projectName))
      .flatMap(_.toOption)
  }

  def find(target: Target): List[TargetId] = {
    val key = TargetId.targetKey(target)
    exec(table.query("targetKey" -> key))
      .flatMap(_.toOption)
      .filter(
        _.matches(target)
      ) // make sure this is not a weird collision due to use of separator in fields
  }
}
