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

  def collectionStats:Map[String, CollectionStats] = Map.empty

  def getAuthorisation(email: String): Option[AuthorisationRecord] = None
  def getAuthorisationList:List[AuthorisationRecord] = Nil
  def setAuthorisation(auth: AuthorisationRecord) {}
  def deleteAuthorisation(email: String) {}

  def createApiKey(newKey: ApiKey) {}
  def getApiKeyList:Iterable[ApiKey] = Nil
  def getApiKey(key: String): Option[ApiKey] = None
  def getAndUpdateApiKey(key: String, counter: Option[String] = None): Option[ApiKey] = None
  def getApiKeyByApplication(application: String): Option[ApiKey] = None
  def deleteApiKey(key: String) {}

}

object Persistence extends Logging {

  object NoOpDataStore extends DataStore with Logging {}

  lazy val store: DataStore = {
    val dataStore = MongoDatastore.buildDatastore().getOrElse(NoOpDataStore)
    log.info("Persistence datastore initialised as %s" format (dataStore))
    dataStore
  }

}


