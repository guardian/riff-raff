package persistence

import play.api.Logger
import controllers.{ApiKey, AuthorisationRecord, Logging}
import magenta.Build
import java.util.UUID
import ci.ContinuousDeploymentConfig
import conf.DatastoreMetrics.DatastoreRequest

trait DataStore extends DocumentStore {
  def log: Logger

  def logAndSquashExceptions[T](message: Option[String], default: T)(block: => T): T = {
    try {
      message.foreach(log.debug(_))
      val value = DatastoreRequest.measure {
        block
      }
      message.foreach(m => log.debug("Completed: %s" format m))
      value
    } catch {
      case t:Throwable =>
        val errorMessage = "Squashing uncaught exception%s" format message.map("whilst %s" format _).getOrElse("")
        log.error(errorMessage, t)
        default
    }
  }

  def logExceptions[T](message: Option[String])(block: => T): Either[Throwable, T] = {
    try {
      message.foreach(log.debug(_))
      val value = DatastoreRequest.measure {
        block
      }
      message.foreach(m => log.debug("Completed: %s" format m))
      Right(value)
    } catch {
      case t:Throwable =>
        val errorMessage = "Caught exception%s" format message.map("whilst %s" format _).getOrElse("")
        log.error(errorMessage, t)
        Left(t)
    }
  }

  def collectionStats:Map[String, CollectionStats] = Map.empty

  def getAuthorisation(email: String): Either[Throwable, Option[AuthorisationRecord]]
  def getAuthorisationList: Either[Throwable, List[AuthorisationRecord]]
  def setAuthorisation(auth: AuthorisationRecord): Either[Throwable, Unit]
  def deleteAuthorisation(email: String): Either[Throwable, Unit]

  def createApiKey(newKey: ApiKey): Unit
  def getApiKeyList:Iterable[ApiKey]
  def getApiKey(key: String): Option[ApiKey]
  def getAndUpdateApiKey(key: String, counter: Option[String] = None): Option[ApiKey]
  def getApiKeyByApplication(application: String): Option[ApiKey]
  def deleteApiKey(key: String): Unit
}

object Persistence extends Logging {

  object NoOpDataStore extends DataStore with Logging {
    def getAuthorisation(email: String) = Right(None)
    def getAuthorisationList = Right(Nil)
    def setAuthorisation(auth: AuthorisationRecord) = Right(())
    def deleteAuthorisation(email: String) = Right(())

    def createApiKey(newKey: ApiKey) {}
    def getApiKeyList = Nil
    def getApiKey(key: String) = None
    def getAndUpdateApiKey(key: String, counter: Option[String] = None) = None
    def getApiKeyByApplication(application: String) = None
    def deleteApiKey(key: String) {}

  }

  lazy val store: DataStore = {
    val dataStore = MongoDatastore.buildDatastore().getOrElse(NoOpDataStore)
    log.info("Persistence datastore initialised as %s" format (dataStore))
    dataStore
  }

}


