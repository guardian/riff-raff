package migration.data

import controllers.{ ApiKey, AuthorisationRecord }
import deployment.PaginationView
import persistence.{ Persistence, DeployRecordDocument, LogDocument, MongoFormat }
import scalaz.zio.{IO, Queue}

trait MongoRetriever[A] {
  implicit def F: MongoFormat[A]

  def getCount: IO[Nothing, Long]

  def getItems(pagination: PaginationView): IO[MigrationError, Iterable[A]]

  def getAllItems(queue: Queue[A], size: Int): IO[MigrationError, _] = {
    def loop(page: Int): IO[MigrationError, _] =
      getItems(PaginationView(Some(size), page)).flatMap { items =>
        if (items.size < size)
          queue.offerAll(items)
        else
          queue.offerAll(items) *> loop(page + 1)
      }

    loop(0)
  }
}

object MongoRetriever {
  def deployRetriever(implicit F0: MongoFormat[DeployRecordDocument]) = new MongoRetriever[DeployRecordDocument] {
    val F = F0
    def getCount = IO.sync(Persistence.store.collectionStats.get("deployV2").map(_.documentCount).getOrElse(0L))
    def getItems(pagination: PaginationView) =
      IO.flatten(IO.sync(IO.fromEither(Persistence.store.getDeploys(None, pagination)))).leftMap(DatabaseError)
    }
    
  def logRetriever(implicit F0: MongoFormat[LogDocument]) = new MongoRetriever[LogDocument] {
    val F = F0
    def getCount = IO.sync(Persistence.store.collectionStats.get("deployV2Logs").map(_.documentCount).getOrElse(0L))
    def getItems(pagination: PaginationView) =
      IO.flatten(IO.sync(IO.fromEither(Persistence.store.readAllLogs(pagination)))).leftMap(DatabaseError)
  }
  
  def authRetriever(implicit F0: MongoFormat[AuthorisationRecord]) = new MongoRetriever[AuthorisationRecord] {
    val F = F0
    def getCount = IO.sync(Persistence.store.collectionStats.get("auth").map(_.documentCount).getOrElse(0L))
    def getItems(pagination: PaginationView) =
      IO.flatten(IO.sync(IO.fromEither(Persistence.store.getAuthorisationList))).leftMap(DatabaseError)
  }
  
  def apiKeyRetriever(implicit F0: MongoFormat[ApiKey]) = new MongoRetriever[ApiKey] {
    val F = F0
    def getCount = IO.sync(Persistence.store.collectionStats.get("apiKeys").map(_.documentCount).getOrElse(0L))
    def getItems(pagination: PaginationView) =
      IO.flatten(IO.sync(IO.fromEither(Persistence.store.getApiKeyList))).leftMap(DatabaseError)
  }

}