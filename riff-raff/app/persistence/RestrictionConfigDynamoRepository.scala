package persistence

import java.util.UUID
import com.gu.scanamo.Table
import restrictions.{RestrictionConfig, RestrictionsConfigRepository}
import cats.syntax.either._
import com.gu.management.Loggable
import conf.Config

class RestrictionConfigDynamoRepository(config: Config) extends DynamoRepository(config) with RestrictionsConfigRepository with Loggable {
  def tablePrefix = "riffraff-restriction-config"

  val table = Table[RestrictionConfig](tableName)

  import com.gu.scanamo.syntax._

  def setRestriction(config: RestrictionConfig): Unit = exec(table.put(config))
  def getRestriction(id: UUID): Option[RestrictionConfig] = exec(table.get('id -> id)).flatMap(_.toOption)
  def deleteRestriction(id: UUID): Unit = exec(table.delete('id -> id))
  def getRestrictionList: Iterable[RestrictionConfig] = {
    val restrictions = exec(table.scan()).flatMap(_.toOption)

    /*
    Renaming whitelist to allowlist is a three step dance:
      1. Add the new field
      2. Read the new field
      3. Remove the old field

     This is step 1.
     */
    restrictions.map(restriction => {
      logger.info(s"adding new allowlist field to restriction ${restriction.id}")
      exec(table.update('id -> restriction.id, set('allowlist -> restriction.whitelist)))
    })

    restrictions
  }

  def getRestrictions(projectName: String): Seq[RestrictionConfig] =
    exec(table.index("restriction-config-projectName").query('projectName -> projectName)).flatMap(_.toOption)
}
