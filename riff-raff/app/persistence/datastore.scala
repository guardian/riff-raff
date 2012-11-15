package persistence

import java.util.UUID
import deployment.DeployRecord
import magenta.MessageStack
import conf.RequestMetrics.DatastoreRequest
import notification.{HookAction, HookCriteria}
import play.api.Play.maybeApplication
import play.api.Logger
import controllers.{AuthorisationRecord, Logging}

trait DataStore {
  def log: Logger

  def logAndSquashExceptions[T](message: Option[String], default: T)(block: => T): T = {
    try {
      message.foreach(log.debug(_))
      DatastoreRequest.measure {
        block
      }
    } catch {
      case t:Throwable =>
        val errorMessage = "Squashing uncaught exception%s" format message.map("whilst %s" format _).getOrElse("")
        log.error(errorMessage, t)
        default
    }
  }

  def dataSize:Long = 0
  def storageSize:Long = 0
  def documentCount:Long = 0

  def getDeploys(limit: Int): Iterable[DeployRecord] = Nil

  def createDeploy(record:DeployRecord) {}
  def updateDeploy(uuid:UUID, stack: MessageStack) {}
  def getDeploy(uuid:UUID):Option[DeployRecord] = None

  def getDeployUUIDs:Iterable[UUID] = Nil
  def deleteDeployLog(uuid:UUID) {}

  def getPostDeployHooks:Map[HookCriteria,HookAction] = Map.empty
  def setPostDeployHook(criteria: HookCriteria, action: HookAction) {}
  def getPostDeployHook(criteria: HookCriteria):Option[HookAction] = None
  def deletePostDeployHook(criteria: HookCriteria) {}

  def getAuthorisation(email: String): Option[AuthorisationRecord] = None
  def getAuthorisationList:List[AuthorisationRecord] = Nil
  def setAuthorisation(auth: AuthorisationRecord) {}
  def deleteAuthorisation(email: String) {}
}

object Persistence extends Logging {

  object NoOpDataStore extends DataStore with Logging {}

  lazy val store: DataStore = {
    val dataStore = MongoDatastore.buildDatastore(maybeApplication).getOrElse(NoOpDataStore)
    log.info("Persistence datastore initialised as %s" format (dataStore))
    dataStore
  }

}


