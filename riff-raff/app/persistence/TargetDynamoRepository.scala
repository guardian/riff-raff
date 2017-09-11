package persistence

import ci.Target
import com.gu.scanamo.Table

case class TargetId(key: String, region: String, stack: String, app: String, id: String)
object TargetId {
  def apply(tgt: Target, id: String): TargetId = TargetId(key(tgt), tgt.region, tgt.stack, tgt.app, id)
  def key(tgt: Target) = s"${tgt.region}|${tgt.stack}|${tgt.app}"
}

object TargetDynamoRepository extends DynamoRepository {
  def tablePrefix = "riffraff-targets"
  val table = Table[TargetId](tableName)

  import com.gu.scanamo.syntax._

  /* not sure this is ideal, we only store one result and not that 'safely'... */
  def set(target: Target, id: String): Unit = exec(table.put(TargetId(target, id)))
  def getId(target: Target): Option[String] = {
    val key = TargetId.key(target)
    exec(table.get('id -> key)).flatMap(_.toOption).map(_.id)
  }

  def getAll: Seq[(Target, String)] =
    exec(table.scan()).flatMap(_.toOption).map{ targetId =>
      Target(targetId.region, targetId.stack, targetId.app) -> targetId.id
    }
}
