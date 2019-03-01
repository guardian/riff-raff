package migration.dsl.interpreters

import migration.data._
import migration.dsl._
import scalaz.zio.IO
import scalikejdbc._

object Postgres {
  def connect(db: String, user: String, password: String): IO[MigrationError, Unit] =
    IO.syncThrowable {
      Class.forName("org.postgresql.Driver")
      ConnectionPool.singleton(db, user, password)
    } mapError(DatabaseError(_))

  val disconnect: IO[Nothing, Unit] = IO.sync(ConnectionPool.closeAll())
}

