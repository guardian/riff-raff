package migration.data

import controllers.{ ApiKey, AuthorisationRecord }
import deployment.PaginationView
import persistence.{ Persistence, DeployRecordDocument, LogDocument, MongoFormat }
import scalaz.zio.{IO, Queue}

trait MongoRetriever[A] {
  implicit def F: MongoFormat[A]

  def getCount: IO[Nothing, Int]

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
            if (items.size < max)
              queue.offerAll(items)
            else
              queue.offerAll(items) *> loop(page + 1, max - items.size)
          }

    loop(1, max)
  }
}

object MongoRetriever {
  def deployRetriever(implicit F0: MongoFormat[DeployRecordDocument]) = new MongoRetriever[DeployRecordDocument] {
    val F = F0
    val getCount = IO.sync(Persistence.store.collectionStats.get("deployV2").map(_.documentCount.toInt).getOrElse(0))
    def getItems(pagination: PaginationView) =
      IO.flatten(IO.sync(IO.fromEither { Persistence.store.getDeploys(None, pagination).map(_.toList) })).leftMap(DatabaseError)
    }
    
  def logRetriever(implicit F0: MongoFormat[LogDocument]) = new MongoRetriever[LogDocument] {
    val F = F0
    val getCount = IO.sync(Persistence.store.collectionStats.get("deployV2Logs").map(_.documentCount.toInt).getOrElse(0))
    def getItems(pagination: PaginationView) =
      IO.flatten(IO.sync(IO.fromEither { Persistence.store.readAllLogs(pagination).map(_.toList) })).leftMap(DatabaseError)
  }
  
  def authRetriever(implicit F0: MongoFormat[AuthorisationRecord]) = new MongoRetriever[AuthorisationRecord] {
    val F = F0
    val getCount = IO.sync(Persistence.store.collectionStats.get("auth").map(_.documentCount.toInt).getOrElse(0))
    def getItems(pagination: PaginationView) =
      IO.flatten(IO.sync(IO.fromEither { Persistence.store.getAuthorisationList(Some(pagination)).map(_.toList) })).leftMap(DatabaseError)
  }
  
  def apiKeyRetriever(implicit F0: MongoFormat[ApiKey]) = new MongoRetriever[ApiKey] {
    val F = F0
    val getCount = IO.sync(Persistence.store.collectionStats.get("apiKeys").map(_.documentCount.toInt).getOrElse(0))
    def getItems(pagination: PaginationView) =
      IO.flatten(IO.sync(IO.fromEither { Persistence.store.getApiKeyList(Some(pagination)).map(_.toList) })).leftMap(DatabaseError)
  }

}