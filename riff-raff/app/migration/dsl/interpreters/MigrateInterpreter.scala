package migration
package dsl
package interpreters

import migration.data._
import cats.~>
import scalaz.zio.IO
import scalikejdbc._

object MigrateInterpreter extends Migrator[Unit] {
  
  val WINDOW_SIZE = 1000

  def dropTable(name: String): IO[MigrationError, Unit] =
    IO.syncThrowable {
      DB autoCommit { implicit session =>
          SQL(s"DROP TABLE IF EXISTS $name").execute.apply()
      }
      ()
    } leftMap(DatabaseError(_))

  def createTable(name: String, idName: String, idType: ColType): IO[MigrationError, Unit] =
    IO.syncThrowable {
      DB autoCommit { implicit session =>
          SQL(s"CREATE TABLE $name ($idName ${idType.toString} PRIMARY KEY, content jsonb NOT NULL)")
          .execute
          .apply()
        ()
      }
    } leftMap(DatabaseError(_))

  def insertAll[A](table: String, id: String, records: List[A])(implicit formatter: ToPostgres[A]): IO[MigrationError, Unit] =
    putStrLn("Inserting items") *>
      IO.traverse(records) { record =>
      IO.syncThrowable {
        DB localTx { implicit session =>
            val json = formatter.json(record).noSpaces
            SQL(s"INSERT INTO $table VALUES ({key}, {content}::json) ON CONFLICT ({keyName}) DO UPDATE SET content = {content2}::jsonb")
              .bindByName('key -> formatter.key(record), 'content -> json, 'keyName -> id, 'content2 -> json)
              .update.apply()
        }
      } leftMap(DatabaseError(_))
    }.void
}