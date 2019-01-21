package migration

import migration.data._
import migration.dsl.interpreters._
import org.mongodb.scala.{ Document => MDocument, _ }
import cats._, cats.implicits._
import scalaz.zio.{ Fiber, IO, App, Ref, Queue, ExitResult }
import scalaz.zio.console._

trait Migrator {

  def WINDOW_SIZE: Int

  def dropTable(name: String): IO[MigrationError, Unit]
  def createTable(name: String, idName: String, idType: ColType): IO[MigrationError, Unit]
  def insertAll[A: ToPostgres](table: String, records: List[A]): IO[MigrationError, _]
  
  def getCollection(database: MongoDatabase, table: String): IO[Nothing, MongoCollection[MDocument]] =
    IO.sync {
      database.getCollection(table)
    }

  def getCount(table: MongoCollection[MDocument]): IO[MigrationError, Long] =
    IO.async[MigrationError, Long] { cb =>
      table.countDocuments().subscribe(new Observer[Long] {
        override def onComplete = ()
        override def onError(e: Throwable) = cb(ExitResult.checked(DatabaseError(e)))
        override def onNext(t: Long) = cb(ExitResult.succeeded(t))
      })
    }

  def getCursor(table: MongoCollection[MDocument]): IO[MigrationError, FindObservable[MDocument]] =
    IO.sync { 
      table.find()
    }

  def getItems[A](cursor: FindObservable[MDocument], limit: Int)(implicit formatter: FromMongo[A]): IO[MigrationError, List[A]] =
    IO.async[MigrationError, List[A]] { cb =>
      val items = collection.mutable.ListBuffer.empty[MDocument]
      cursor.subscribe(new Observer[MDocument] {
        override def onNext(result: MDocument): Unit = items += result
        override def onError(t: Throwable): Unit = cb(ExitResult.checked(DatabaseError(t)))
        override def onComplete: Unit =
          items.toList.traverse(formatter.parseMongo) match {
              case Some(as) => cb(ExitResult.succeeded(as))
              case None     => cb(ExitResult.checked(DecodingError))
          }
        override def onSubscribe(subscription: Subscription): Unit =
          subscription.request(limit)
      })
    }


  def migrate[A: FromMongo: ToPostgres](
    mongoDb: MongoDatabase, 
    mongoTable: String, 
    pgTable: PgTable[A]
  ): IO[MigrationError, (Ref[Long], Fiber[MigrationError, _], Fiber[MigrationError, _])] =
    for {
      _ <- putStr(s"Starting migration $mongoTable -> ${pgTable.name}... ")
      coll <- getCollection(mongoDb, mongoTable)
      n <- getCount(coll)
      cpt <- Ref(n)
      _ <- putStrLn(s"Found $n items to migrate")
      _ <- dropTable(pgTable.name)
      _ <- createTable(pgTable.name, pgTable.id, pgTable.idType)
      _ <- putStr(s"Moving items...")
      q <- Queue.bounded[A](WINDOW_SIZE)
      cursor <- getCursor(coll)
      f1 <- getAllItems(q, cursor).fork
      f2 <- insertAllItems(q, cpt, pgTable).fork
    } yield (cpt, f1, f2)

  def getAllItems[A: FromMongo](queue: Queue[A], cursor: FindObservable[MDocument]): IO[MigrationError, _] = {
    def loop: IO[MigrationError, _] =
      getItems[A](cursor, WINDOW_SIZE).flatMap { items =>
        if (items.length < WINDOW_SIZE)
          queue.offerAll(items)
        else
          queue.offerAll(items) *> loop
      }

    loop
  }

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