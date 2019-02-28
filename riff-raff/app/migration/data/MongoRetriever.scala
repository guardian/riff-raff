package migration.data

import controllers.{ ApiKey, AuthorisationRecord }
import deployment.PaginationView
import persistence.{ DataStore, DeployRecordDocument, LogDocument, MongoFormat }
import scalaz.zio.{IO, Queue}
import org.joda.time.DateTime

trait MongoRetriever[A] {
  implicit def F: MongoFormat[A]

  def getCount: IO[MigrationError, Int]

  def getItems(pagination: PaginationView): IO[MigrationError, Iterable[A]]

  def getAllItems(queue: Queue[A], size: Int, max: Int): IO[MigrationError, _] = {
    def loop(page: Int, max: Int): IO[MigrationError, _] =
      if (max <= 0)
        IO.unit
      else
        getItems(PaginationView(Some(size), page))
          // `size` may be larger than `max`, 
          .map(_.take(max))
          .flatMap { items =>
            // we may also get less elements than required
            // which indicates the end of the table
            if (items.size < size)
              queue.offerAll(items)
            else
              queue.offerAll(items) *> loop(page + 1, max - items.size)
          }

    loop(1, max)
  }
}

object MongoRetriever {
  def deployRetriever(datastore: DataStore)(implicit F0: MongoFormat[DeployRecordDocument]) = new MongoRetriever[DeployRecordDocument] {
    val F = F0
    val getCount = IO.blocking(datastore.collectionStats.get("deployV2").map(_.documentCount.toInt).getOrElse(0)).mapError(DatabaseError)
    def getItems(pagination: PaginationView) =
      IO.blocking { datastore.getDeploys(None, pagination) }.absolve.mapError(DatabaseError)
    }
    
  def logRetriever(datastore: DataStore)(implicit F0: MongoFormat[LogDocument]) = new MongoRetriever[LogDocument] {
    val deployLogDateFrom = new DateTime("2019-02-18T00:00:00.000Z")
    val F = F0
    val getCount = IO.blocking(datastore.collectionStats.get("deployV2Logs").map(_.documentCount.toInt).getOrElse(0)).mapError(DatabaseError)
    def getItems(pagination: PaginationView) =
      IO.blocking { datastore.readAllLogs(pagination) }.absolve.mapError(DatabaseError)
  }
  
  def authRetriever(datastore: DataStore)(implicit F0: MongoFormat[AuthorisationRecord]) = new MongoRetriever[AuthorisationRecord] {
    val F = F0
    val getCount = IO.blocking(datastore.collectionStats.get("auth").map(_.documentCount.toInt).getOrElse(0)).mapError(DatabaseError)
    def getItems(pagination: PaginationView) =
      IO.blocking { datastore.getAuthorisationList(Some(pagination)) }.absolve.mapError(DatabaseError)
  }
  
  def apiKeyRetriever(datastore: DataStore)(implicit F0: MongoFormat[ApiKey]) = new MongoRetriever[ApiKey] {
    val F = F0
    val getCount = IO.blocking(datastore.collectionStats.get("apiKeys").map(_.documentCount.toInt).getOrElse(0)).mapError(DatabaseError)
    def getItems(pagination: PaginationView) =
      IO.blocking { datastore.getApiKeyList(Some(pagination)) }.absolve.mapError(DatabaseError)
  }

}