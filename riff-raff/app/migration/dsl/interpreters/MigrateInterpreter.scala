package migration
package dsl
package interpreters

import migration.data._
import cats.~>
import play.api.libs.json.Json
import scalaz.zio.IO
import scalikejdbc._

object MigrateInterpreter extends Migrator[Unit] with controllers.Logging {
  
  val WINDOW_SIZE = 1000

  def zero = ()
  def combine(r1: Unit, r2: Unit) = ()

  def deleteTable(pgTable: ToPostgres[_]): IO[MigrationError, Unit] =
    IO.sync(log.info("Creating table")) *>
      IO.blocking {
        DB autoCommit { implicit session =>
          pgTable.delete.execute.apply()
        }
      }.void mapError(DatabaseError(_))

  def insertAll[A](pgTable: ToPostgres[A], records: List[A]): IO[MigrationError, Unit] = {
    val keyJsonPairs = records.map(rec => pgTable.key(rec) -> Json.stringify(pgTable.json(rec)))
    IO.sync(log.info(s"Inserting ${records.length} items")) *>
      IO.foreach(keyJsonPairs.grouped(100).toList) { records10 =>
        IO.blocking {
          DB localTx { implicit session =>
            records10.foreach { case (key, json) => pgTable.insert(key, json).update.apply() }
          }
        } mapError(DatabaseError(_))
      }.void
    }
}