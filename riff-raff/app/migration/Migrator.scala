package migration

import migration.data._
import migration.dsl.interpreters._
import persistence.MongoFormat
import cats._, cats.implicits._
import scalaz.zio.{ Fiber, IO, App, Ref, Queue, ExitResult }

trait Migrator[Response] {

  def WINDOW_SIZE: Int

  def dropTable(pgTable: ToPostgres[_]): IO[MigrationError, Response]
  def createTable(pgTable: ToPostgres[_]): IO[MigrationError, Response]
  def insertAll[A](pgTable: ToPostgres[A], records: List[A]): IO[MigrationError, Response]
  
  def migrate[A: MongoFormat: ToPostgres](
    retriever: MongoRetriever[A], 
    limit: Option[Int]
  ): IO[MigrationError, (Ref[Int], Fiber[MigrationError, _], Fiber[MigrationError, List[Response]], Response, Response)] =
    for {
      // n is the maximum number of items we want to retrieve, by default the whole table
      n <- retriever.getCount.map(max => limit.map(Math.min(max, _)).getOrElse(max))
      window = Math.min(n, WINDOW_SIZE)
      cpt <- Ref(n)
      r1 <- dropTable(ToPostgres[A])
      r2 <- createTable(ToPostgres[A])
      // we're going to paginate through the results to reduce memory overhead
      q <- Queue.bounded[A](window * 10)
      f1 <- retriever.getAllItems(q, window, n).fork
      f2 <- insertAllItems(q, cpt, window).fork
    } yield (cpt, f1, f2, r1, r2)

  def insertAllItems[A: ToPostgres](queue: Queue[A], counter: Ref[Int], window: Int): IO[MigrationError, List[Response]] = {
    def loop(resps: List[Response]): IO[MigrationError, List[Response]] = {
      def loop_in(n: Int): IO[MigrationError, List[A]] =
        queue.takeUpTo(window).flatMap { items =>
          if (items.length >= n)
            IO.now(items.take(n))
          else if (items.length == 0)
            loop_in(n)
          else
            loop_in(n - items.length).map(items ++ _)
        }

      for {
        n <- counter.get
        rs <- if (n <= 0)
          IO.now(resps)
        else {
          val max = Math.min(n, window)
          loop_in(max).flatMap { items =>
            IO.flatten(counter.modify { n => 
              val op = insertAll(ToPostgres[A], items).flatMap { r => 
                if (n - items.length <= 0)
                  IO.now(r :: resps)
                else
                  loop(r :: resps)
              }
              (op, n - items.length)
            })
          }
        }
      } yield rs
    }

    loop(Nil)
  }
    
}