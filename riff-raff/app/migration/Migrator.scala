package migration

import migration.data._
import migration.dsl.interpreters.PgTable
import migration.interop.MonadIO
import migration.interop.MonadIO._
import org.mongodb.scala.{ Document => MDocument, _ }
import scalaz._, Scalaz._
import scalaz.zio.{ Fiber, IO, App, Queue }
import scalaz.zio.interop.scalaz72._
import scalaz.zio.interop.console.scalaz._

trait Migrator {

  def WINDOW_SIZE: Int
  
  def migrate[A](
    cfg: Config, 
    mongoDb: MongoDatabase, 
    mongoTable: String, 
    pgTable: PgTable[A]
  )(implicit F: FromMongo[A], T: ToPostgres[A]): Migration[MigrationError, Unit] =
    for {
      _ <- putStr(s"Starting migration $mongoTable -> ${pgTable.name}... ").leftMap[MigrationError](ConsoleError(_)).lift[Migration]
      coll <- getCollection(mongoDb, mongoTable)
      n <- getCount(coll)
      _ <- putStrLn(s"Found $n items to migrate").leftMap[MigrationError](ConsoleError(_)).lift[Migration]
      _ <- dropTable(pgTable.name)
      _ <- createTable(pgTable.name, pgTable.id, pgTable.idType)
      _ <- putStr(s"Moving items...").leftMap[MigrationError](ConsoleError(_)).lift[Migration]
      q <- Queue.bounded[A](WINDOW_SIZE).lift[Migration]
      cursor <- getCursor(coll)
      fs <- getAllItems(q, cursor)
      f2 <- insertAllItems(q, pgTable)
      fs <- Fiber.joinAll(fs).lift[Migration]
    } yield ()

  def getAllItems[A](
    queue: Queue[A], 
    cursor: FindObservable[MDocument]
  )(implicit F: FromMongo[A]): Migration[MigrationError, List[Fiber[MigrationError, _]]] = {
    def loop(acc: List[Fiber[MigrationError, _]]): Migration[MigrationError, List[Fiber[MigrationError, _]]] =
      getItems[A](cursor, WINDOW_SIZE).flatMap { items =>
        if (items.length < WINDOW_SIZE)
          queue.offerAll(items).fork.lift[Migration].map(_ :: acc)
        else
          queue.offerAll(items).fork.lift[Migration].flatMap(f => loop(f :: acc))
      }

    loop(Nil)
  }

  def insertAllItems[A](queue: Queue[A], pgTable: PgTable[A])(implicit T: ToPostgres[A]): Migration[MigrationError, Unit] =
    queue.takeUpTo(WINDOW_SIZE).lift[Migration].flatMap { items =>
      if (items.length < WINDOW_SIZE)
        insertAll(pgTable.name, items)
      else
        insertAll(pgTable.name, items).flatMap(_ => insertAllItems(queue, pgTable))
    }
}