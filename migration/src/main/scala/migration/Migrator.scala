package migration

import migration.data._
import migration.dsl.interpreters.PgTable
import migration.interop.MonadIO
import migration.interop.MonadIO._
import org.mongodb.scala._
import scalaz._, Scalaz._
import scalaz.zio.{ IO, App }
import scalaz.zio.interop.scalaz72._
import scalaz.zio.interop.console.scalaz._

trait Migrator {

  def WINDOW_SIZE: Int
  
  def migrate[A](
    cfg: Config, 
    mongoDb: MongoDatabase, 
    mongoTable: String, 
    pgTable: PgTable[A]
  )(implicit F: FromMongo[A], T: ToPostgre[A]): Migration[MigrationError, Unit] =
    for {
      _ <- putStr(s"Starting migration $mongoTable -> ${pgTable.name}... ").leftMap[MigrationError](ConsoleError(_)).lift[Migration]
      coll <- getCollection(mongoDb, mongoTable)
      n <- getCount(coll)
      _ <- putStrLn(s"Found $n items to migrate").leftMap[MigrationError](ConsoleError(_)).lift[Migration]
      _ <- dropTable(pgTable.name)
      _ <- createTable(pgTable.name, pgTable.id, pgTable.idType)

      iterations = Math.ceil(n / WINDOW_SIZE).toInt

      _ <- (0 until iterations).toList.traverse { i =>
        val skip = i * WINDOW_SIZE
        val limit = WINDOW_SIZE.toLong.min(n - skip.toLong).toInt
        for {
          _ <- putStr(s"Moving items ${skip} to ${skip + limit}...").leftMap[MigrationError](ConsoleError(_)).lift[Migration]
          // TODO log errors
          recs <- getCursor[A](coll, skip, limit)
          // TODO log errors
          _ <- insertAll[A](pgTable.name, recs)
        } yield ()
      }

    } yield ()

}