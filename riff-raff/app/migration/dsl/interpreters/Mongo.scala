package migration.dsl.interpreters

import migration.data.{ Document => _, _ }
import org.mongodb.scala._
import scalaz._, Scalaz._
import scalaz.zio.{IO, ExitResult}

object Mongo {
  val dbName = "riffraff"

  def connect(uri: String): IO[MigrationError, (MongoClient, MongoDatabase)] =
    IO.syncThrowable {
      val mongoClient = MongoClient(uri)
      mongoClient -> mongoClient.getDatabase(dbName)
    } leftMap(_ => MissingDatabase(uri ++ "/" ++ dbName))

  def disconnect(client: MongoClient): IO[Nothing, Unit] =
    IO.sync(client.close())

  def getCollection(database: MongoDatabase, table: String): IO[Nothing, MongoCollection[Document]] =
    IO.sync {
      database.getCollection(table)
    }

  def getCount(table: MongoCollection[Document]): IO[MigrationError, Long] =
    IO.async[MigrationError, Long] { cb =>
      table.countDocuments().subscribe(new Observer[Long] {
        override def onComplete = ()
        override def onError(e: Throwable) = cb(ExitResult.checked(DatabaseError(e)))
        override def onNext(t: Long) = cb(ExitResult.succeeded(t))
      })
    }

  def getCursor(table: MongoCollection[Document]): IO[MigrationError, FindObservable[Document]] =
    IO.sync { 
      table.find()
    }

  def getItems[A](cursor: FindObservable[Document], limit: Int, formatter: FromMongo[A]): IO[MigrationError, List[A]] =
    IO.async[MigrationError, List[A]] { cb =>
      val items = collection.mutable.ListBuffer.empty[Document]
      cursor.subscribe(new Observer[Document] {
        override def onNext(result: Document): Unit = items += result
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
}

