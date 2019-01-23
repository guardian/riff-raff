package migration.data

import controllers.{ ApiKey, AuthorisationRecord }
import persistence.{ Persistence, DeployRecordDocument, LogDocument, MongoFormat }
import scalaz.zio.IO

trait MongoRetriever[A] {
  def getCount: IO[Nothing, Long]
  def getAllItems(implicit F: MongoFormat[A]): IO[MigrationError, Iterable[A]]
}

object MongoRetriever {
  object DeployRetriever extends MongoRetriever[DeployRecordDocument] {
    def getCount = IO.sync(Persistence.store.collectionStats.get("deployV2").map(_.documentCount).getOrElse(0L))
    def getAllItems(implicit F: MongoFormat[DeployRecordDocument]) = ???
  }
  
  object LogRetriever extends MongoRetriever[LogDocument] {
    def getCount = IO.sync(Persistence.store.collectionStats.get("deployV2Logs").map(_.documentCount).getOrElse(0L))
    def getAllItems(implicit F: MongoFormat[LogDocument]) = ???
  }
  
  object AuthRetriever extends MongoRetriever[AuthorisationRecord] {
    def getCount = IO.sync(Persistence.store.collectionStats.get("auth").map(_.documentCount).getOrElse(0L))
    def getAllItems(implicit F: MongoFormat[AuthorisationRecord]) =
      IO.flatten(IO.sync(IO.fromEither(Persistence.store.getAuthorisationList))).leftMap(DatabaseError)
  }
  
  object ApiKeyRetriever extends MongoRetriever[ApiKey] {
    def getCount = IO.sync(Persistence.store.collectionStats.get("apiKeys").map(_.documentCount).getOrElse(0L))
    def getAllItems(implicit F: MongoFormat[ApiKey]) = 
      IO.flatten(IO.sync(IO.fromEither(Persistence.store.getApiKeyList))).leftMap(DatabaseError)
  }

}