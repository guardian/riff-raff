package migration

import migration.data._
import migration.dsl.interpreters._
import persistence.MongoFormat
import cats._, cats.implicits._
import scalaz.zio.{ Fiber, IO, App, Ref, Queue, Exit }

trait Migrator[Response] extends controllers.Logging {

  def WINDOW_SIZE: Int

  def deleteTable(pgTable: ToPostgres[_]): IO[MigrationError, Response]
  def insertAll[A](pgTable: ToPostgres[A], records: List[A]): IO[MigrationError, Response]
  
  def migrate[A: MongoFormat: ToPostgres](
    retriever: MongoRetriever[A], 
    limit: Option[Int]
  ): IO[MigrationError, (Ref[Int], Fiber[MigrationError, _], Fiber[MigrationError, List[Response]], Response)] =
    for {
      // n is the maximum number of items we want to retrieve, by default the whole table
      n <- retriever.getCount.map(max => limit.map(Math.min(max, _)).getOrElse(max))
      _ <- IO.sync(log.info(s"$n items to migrate"))
      window = Math.min(n, WINDOW_SIZE)
      cpt <- Ref.make(n)
      r <- deleteTable(ToPostgres[A])
      // we're going to paginate through the results to reduce memory overhead
      q <- Queue.bounded[A](window * 10)
      f1 <- retriever.getAllItems(q, window, n).fork
      f2 <- insertAllItems(q, cpt, window).fork
    } yield (cpt, f1, f2, r)

  def insertAllItems[A: ToPostgres](queue: Queue[A], counter: Ref[Int], window: Int): IO[MigrationError, List[Response]] = {
    def loop(resps: List[Response]): IO[MigrationError, List[Response]] = {
      def loop_in(n: Int): IO[MigrationError, List[A]] =
        queue.takeUpTo(window).flatMap { items =>
          if (items.length >= n)
            IO.succeed(items.take(n))
          else if (items.length == 0)
            loop_in(n)
          else
            loop_in(n - items.length).map(items ++ _)
        }

      for {
        n <- counter.get
        rs <- if (n <= 0)
          IO.succeed(resps)
        else {
          val max = Math.min(n, window)
          loop_in(max).flatMap { items =>
            IO.flatten(counter.modify { n => 
              val op = insertAll(ToPostgres[A], items).flatMap { r => 
                if (n - items.length <= 0)
                  IO.succeed(r :: resps)
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