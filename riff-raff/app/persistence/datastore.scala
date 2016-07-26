package persistence

import notification.{HookConfig, HookAction, HookCriteria}
import play.api.Play.maybeApplication
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

  def getPostDeployHooks:Map[HookCriteria,HookAction] = Map.empty
  def setPostDeployHook(criteria: HookCriteria, action: HookAction) {}
  def getPostDeployHook(criteria: HookCriteria):Option[HookAction] = None
  def deletePostDeployHook(criteria: HookCriteria) {}

  def getPostDeployHook(id: UUID): Option[HookConfig] = None
  def getPostDeployHook(projectName: String, stage: String): Iterable[HookConfig] = Nil
  def getPostDeployHookList:Iterable[HookConfig] = Nil
  def setPostDeployHook(config: HookConfig) {}
  def deletePostDeployHook(id: UUID) {}

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

  def writeKey(key:String, value:String) {}
  def readKey(key:String): Option[String] = None
  def deleteKey(key: String) {}
}

object Persistence extends Logging {

  object NoOpDataStore extends DataStore with Logging {}

  lazy val store: DataStore = {
    val dataStore = MongoDatastore.buildDatastore(maybeApplication).getOrElse(NoOpDataStore)
    log.info("Persistence datastore initialised as %s" format (dataStore))
    dataStore
  }

}


