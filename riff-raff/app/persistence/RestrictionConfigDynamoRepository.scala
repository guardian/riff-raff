package persistence

import java.util.UUID

import org.scanamo.Table
import restrictions.{RestrictionConfig, RestrictionsConfigRepository}
import cats.syntax.either._
import conf.Config
import org.scanamo.generic.auto._

class RestrictionConfigDynamoRepository(config: Config)
    extends DynamoRepository(config)
    with RestrictionsConfigRepository {
  def tablePrefix = "riffraff-restriction-config"

  val table = Table[RestrictionConfig](tableName)

  import org.scanamo.syntax._

  def setRestriction(config: RestrictionConfig): Unit = exec(table.put(config))
  def getRestriction(id: UUID): Option[RestrictionConfig] =
    exec(table.get("id" -> id)).flatMap(_.toOption)
  def deleteRestriction(id: UUID): Unit = exec(table.delete("id" -> id))
  def getRestrictionList: Iterable[RestrictionConfig] =
    exec(table.scan()).flatMap(_.toOption)

  def getRestrictions(projectName: String): Seq[RestrictionConfig] =
    exec(
      table
        .index("restriction-config-projectName")
        .query("projectName" -> projectName)
    ).flatMap(_.toOption)
}
