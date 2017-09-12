package persistence

import ci.Target
import com.amazonaws.services.dynamodbv2.model.PutItemResult
import com.gu.scanamo.Table
import org.joda.time.DateTime

case class TargetId(id: String, targetKey: String, region: String, stack: String, app: String, projectName: String, lastSeen: DateTime) {
  def matches(target: Target): Boolean = region == target.region && stack == target.stack && app == target.app
}
object TargetId {
  def apply(tgt: Target, projectName: String, lastSeen: DateTime): TargetId =
    TargetId(id(tgt, projectName), targetKey(tgt), tgt.region, tgt.stack, tgt.app, projectName, lastSeen)
  def targetKey(tgt: Target) = Seq(tgt.region,tgt.stack,tgt.app).mkString("|")
  def id(tgt: Target, projectName: String) = Seq(tgt.region,tgt.stack,tgt.app,projectName).mkString("|")
}

object TargetDynamoRepository extends DynamoRepository {
  def tablePrefix = "riffraff-targets"
  val table = Table[TargetId](tableName)

  import com.gu.scanamo.syntax._

  def set(target: Target, projectName: String, lastSeen: DateTime): PutItemResult = exec(table.put(TargetId(target, projectName, lastSeen)))

  def get(id: String): Option[TargetId] = {
    exec(table.get('id -> id)).flatMap(_.toOption)
  }

  def get(target: Target): List[TargetId] = {
    val key = TargetId.targetKey(target)
    exec(table.index("riffraff-targets-targetKey").query('targetKey -> key))
      .flatMap(_.toOption)
      .filter(_.matches(target)) // make sure this is not a weird collision due to use of separator in fields
  }

  def getAll: Seq[TargetId] = exec(table.scan()).flatMap(_.toOption)
}
