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
      IO.syncThrowable {
        DB autoCommit { implicit session =>
          pgTable.drop.execute.apply()
        }
        ()
      }.unyielding mapError(DatabaseError(_))

  def createTable(pgTable: ToPostgres[_]): IO[MigrationError, Unit] =
    IO.sync(log.info("Creating table")) *>
      IO.syncThrowable {
        DB autoCommit { implicit session =>
          pgTable.create.execute.apply()
        }
        ()
      }.unyielding mapError(DatabaseError(_))

  def insertAll[A](pgTable: ToPostgres[A], records: List[A]): IO[MigrationError, Unit] =
    IO.sync(log.info(s"Inserting ${records.length} items")) *>
      IO.foreach(records) { record =>
        IO.syncThrowable {
          DB localTx { implicit session =>
            pgTable.insert(pgTable.key(record), Json.stringify(pgTable.json(record))).update.apply()
          }
        }.unyielding mapError(DatabaseError(_))
      }.void
}