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

  def dropTable(pgTable: ToPostgres[_]): IO[MigrationError, Unit] =
    IO.sync(log.info("Dropping table")) *>
      IO.blocking {
        DB autoCommit { implicit session =>
          pgTable.drop.execute.apply()
        }
        ()
      } mapError(DatabaseError(_))

  def createTable(pgTable: ToPostgres[_]): IO[MigrationError, Unit] =
    IO.sync(log.info("Creating table")) *> IO.blocking(pgTable.create).void.mapError(DatabaseError(_))

  def insertAll[A](pgTable: ToPostgres[A], records: List[A]): IO[MigrationError, Unit] =
    IO.sync(log.info(s"Inserting ${records.length} items")) *>
      IO.foreach(records.grouped(10).toList) { records10 =>
        IO.blocking {
          DB localTx { implicit session =>
            records10.foreach { record => 
              pgTable.insert(pgTable.key(record), Json.stringify(pgTable.json(record))).update.apply()
            }
          }
        } mapError(DatabaseError(_))
      }.void
}