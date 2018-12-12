package migration.dsl.interpreters

import migration.data.{ Document => _, _ }
import org.mongodb.scala._
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

}

