package migration
package dsl
package interpreters

import migration.data._
import cats.~>
import play.api.libs.json.Json
import scalaz.zio.IO
import scalaz.zio.console._
import scalikejdbc._

object MigrateInterpreter extends Migrator[Unit] {
  
  val WINDOW_SIZE = 1000

  def dropTable(pgTable: ToPostgres[_]): IO[MigrationError, Unit] =
    putStrLn("Dropping table") *>
      IO.syncThrowable {
        DB autoCommit { implicit session =>
          pgTable.drop.execute.apply()
        }
        ()
      }.unyielding leftMap(DatabaseError(_))

  def createTable(pgTable: ToPostgres[_]): IO[MigrationError, Unit] =
    putStrLn("Creating table") *>
      IO.syncThrowable {
        DB autoCommit { implicit session =>
          pgTable.create.execute.apply()
        }
        ()
      }.unyielding leftMap(DatabaseError(_))

  def insertAll[A](pgTable: ToPostgres[A], records: List[A]): IO[MigrationError, Unit] =
    putStrLn("Inserting items") *>
      IO.traverse(records) { record =>
        IO.syncThrowable {
          DB localTx { implicit session =>
            pgTable.insert(pgTable.key(record), Json.stringify(pgTable.json(record))).update.apply()
          }
        }.unyielding leftMap(DatabaseError(_))
      }.void
}