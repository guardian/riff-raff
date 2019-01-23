package migration

import migration.data._
import migration.dsl.interpreters._
import org.mongodb.scala.{ Document => MDocument, _ }
import persistence.MongoFormat
import cats._, cats.implicits._
import scalaz.zio.{ Fiber, IO, App, Ref, Queue, ExitResult }
import scalaz.zio.console._

trait Migrator {

  def WINDOW_SIZE: Int

  def dropTable(name: String): IO[MigrationError, Unit]
  def createTable(name: String, idName: String, idType: ColType): IO[MigrationError, Unit]
  def insertAll[A: ToPostgres](table: String, records: List[A]): IO[MigrationError, _]
  
  def migrate[A: MongoFormat: ToPostgres](
    retriever: MongoRetriever[A], 
    pgTable: PgTable[A],
    limit: Option[Long]
  ): IO[MigrationError, (Ref[Long], Fiber[MigrationError, _], Fiber[MigrationError, _])] =
    for {
      _ <- putStrLn(s"Starting migration to ${pgTable.name}... ")
      n <- limit.map(IO.now).getOrElse(retriever.getCount)
      cpt <- Ref(n)
      _ <- putStrLn(s"Found $n items to migrate")
      _ <- dropTable(pgTable.name)
      _ <- createTable(pgTable.name, pgTable.id, pgTable.idType)
      _ <- putStrLn(s"Moving items...")
      q <- Queue.bounded[A](WINDOW_SIZE)
      f1 <- retriever.getAllItems(q, WINDOW_SIZE).fork
      f2 <- insertAllItems(q, cpt, pgTable).fork
    } yield (cpt, f1, f2)

  def insertAllItems[A: ToPostgres](queue: Queue[A], counter: Ref[Long], pgTable: PgTable[A]): IO[MigrationError, _] = {
    def loop: IO[MigrationError, _] =
      queue.takeUpTo(WINDOW_SIZE).flatMap { items =>
        IO.flatten(counter.modify { n => 
          val op = if (items.length < WINDOW_SIZE)
            insertAll(pgTable.name, items)
          else
            insertAll(pgTable.name, items) *> loop
          (op, n - items.length)
        })
      }

    loop
  }
    
}