package migration.dsl.interpreters

import migration.data._
import migration.dsl._
import scalaz.zio.IO
import scalikejdbc._

final case class PgTable[A](name: String, id: String, idType: ColType)

object Postgre {
  def connect(db: String, user: String, password: String): IO[MigrationError, Unit] =
    IO.syncThrowable {
      Class.forName("org.postgresql.Driver")
      ConnectionPool.singleton(db, user, password)
    } leftMap(DatabaseError(_))

  val disconnect: IO[Nothing, Unit] = IO.sync(ConnectionPool.closeAll())

  def dropTable(name: String): IO[MigrationError, Unit] =
    IO.syncThrowable {
      DB autoCommit { implicit session =>
        sql"DROP TABLE IF EXISTS ${name}".execute.apply()
      }
      ()
    } leftMap(DatabaseError(_))

  def createTable(name: String, idName: String, idType: ColType): IO[MigrationError, Unit] =
    IO.syncThrowable {
      DB autoCommit { implicit session =>
        sql"CREATE TABLE ${name} (${idName} {idType} PRIMARY KEY, content jsonb)"
          .bindByName('idType -> idType.toString)
          .execute
          .apply()
        ()
      }
    } leftMap(DatabaseError(_))

  def insertAll[A](table: String, records: List[A], formatter: ToPostgre[A]): IO[MigrationError, Unit] =
    IO.traverse(records.grouped(100).toList) { records =>
      IO.syncThrowable {
        DB localTx { implicit session =>
          withSQL {
            insert.into(new SQLSyntaxSupport[A] { override val tableName = table })
              .values(records.map(r => (formatter.key(r).toString, formatter.json(r).noSpaces)))
          }.update.apply()
        }
      } leftMap(DatabaseError(_))
    }.void

}

