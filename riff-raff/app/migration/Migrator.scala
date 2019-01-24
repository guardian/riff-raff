package migration

import migration.data._
import migration.dsl.interpreters._
import persistence.MongoFormat
import cats._, cats.implicits._
import scalaz.zio.{ Fiber, IO, App, Ref, Queue, ExitResult }
import scalaz.zio.console._

trait Migrator[Response] {

  def WINDOW_SIZE: Int

  def dropTable(name: String): IO[MigrationError, Response]
  def createTable(name: String, idName: String, idType: ColType): IO[MigrationError, Response]
  def insertAll[A: ToPostgres](table: String, records: List[A]): IO[MigrationError, Response]
  
  def migrate[A: MongoFormat: ToPostgres](
    retriever: MongoRetriever[A], 
    pgTable: PgTable[A],
    limit: Option[Int]
  ): IO[MigrationError, (Ref[Int], Fiber[MigrationError, _], Fiber[MigrationError, List[Response]])] =
    for {
      n <- limit.map(IO.now).getOrElse(retriever.getCount)
      cpt <- Ref(n)
      _ <- dropTable(pgTable.name)
      _ <- createTable(pgTable.name, pgTable.id, pgTable.idType)
      window = limit.map(Math.min(_, WINDOW_SIZE)).getOrElse(WINDOW_SIZE)
      q <- Queue.bounded[A](window)
      f1 <- retriever.getAllItems(q, window, n).fork
      f2 <- insertAllItems(q, cpt, pgTable, window).fork
    } yield (cpt, f1, f2)

  def insertAllItems[A: ToPostgres](queue: Queue[A], counter: Ref[Int], pgTable: PgTable[A], window: Int): IO[MigrationError, List[Response]] = {
    def loop(resps: List[Response]): IO[MigrationError, List[Response]] = for {
      n <- counter.get
      rs <- if (n <= 0)
        IO.now(resps)
      else
        queue.takeUpTo(window).flatMap { items =>
          if (items.length == 0)
            loop(resps)
          else
            IO.flatten(counter.modify { n => 
              val op = if (items.length < window)
                insertAll(pgTable.name, items).flatMap(r => IO.now(r :: resps))
              else
                insertAll(pgTable.name, items).flatMap(r => loop(r :: resps))
              (op, n - items.length)
            })
        }
    } yield rs

    loop(Nil)
  }
    
}