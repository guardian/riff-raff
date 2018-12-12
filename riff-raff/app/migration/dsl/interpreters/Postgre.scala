package migration.dsl.interpreters

import migration.data._
import migration.dsl._
import scalaz.zio.IO
import scalikejdbc._

final case class PgTable[A](name: String, id: String, idType: ColType)

object Postgres {
  def connect(db: String, user: String, password: String): IO[MigrationError, Unit] =
    IO.syncThrowable {
      Class.forName("org.postgresql.Driver")
      ConnectionPool.singleton(db, user, password)
    } leftMap(DatabaseError(_))

  val disconnect: IO[Nothing, Unit] = IO.sync(ConnectionPool.closeAll())
}

