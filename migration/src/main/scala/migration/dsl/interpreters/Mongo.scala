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
        override def onError(e: Throwable) = cb(ExitResult.Failed(DatabaseError(e)))
        override def onNext(t: Long) = cb(ExitResult.Completed(t))
      })
    }

  def getCursor[A](table: MongoCollection[Document], skip: Int, limit: Int, format: FromMongo[A]): IO[MigrationError, List[A]] =
    IO.async { cb: (ExitResult[MigrationError, List[A]] => Unit) =>
      val items = collection.mutable.ArrayBuffer.empty[Document]
      table.find().skip(skip).limit(limit).subscribe(new Observer[Document] {
        override def onComplete = 
          items.toList.traverse(format.parseMongo) match {
            case Some(as) => cb(ExitResult.Completed(as))
            case None     => cb(ExitResult.Failed(DecodingError))
          }
        override def onError(e: Throwable) = cb(ExitResult.Failed(DatabaseError(e)))
        override def onNext(result: Document) = items += result
      })
    }
}

