package persistence

import java.util.UUID

import conf.Config
import conf.DatastoreMetrics.DatastoreRequest
import controllers.{ApiKey, AuthorisationRecord, Logging}
import org.joda.time.DateTime
import play.api.Logger
import utils.Retriable

trait DataStore extends DocumentStore with Retriable {
  def log: Logger

  def logAndSquashExceptions[T](message: Option[String], default: T)(block: => T): T =
    logExceptions(message)(block).fold(_ => default, identity)

  def logExceptions[T](message: Option[String])(block: => T): Either[Throwable, T] =
    try {
      val result = run(block)
      message.foreach(m => log.debug("Completed: $s" format m))
      Right(result)
    } catch {
      case t: Throwable =>
        val error = "Caught exception%s" format message.map(" whilst %s" format _).getOrElse("")
        log.error(error, t)
        Left(t)
    }

  def run[T](block: => T): T = DatastoreRequest.measure(block)

  def collectionStats: Map[String, CollectionStats] = Map.empty

  def getAuthorisation(email: String): Either[Throwable, Option[AuthorisationRecord]]
  def getAuthorisationList: Either[Throwable, List[AuthorisationRecord]]
  def setAuthorisation(auth: AuthorisationRecord): Either[Throwable, Unit]
  def deleteAuthorisation(email: String): Either[Throwable, Unit]

  def createApiKey(newKey: ApiKey): Unit
  def getApiKeyList: Either[Throwable, Iterable[ApiKey]]
  def getApiKey(key: String): Option[ApiKey]
  def getAndUpdateApiKey(key: String, counter: Option[String] = None): Option[ApiKey]
  def getApiKeyByApplication(application: String): Option[ApiKey]
  def deleteApiKey(key: String): Unit
}

object Persistence extends Logging {

  object NoOpDataStore extends DataStore with Logging {
    private val none = Right(None)
    private val nil = Right(Nil)
    private val unit = Right(())

    def getAuthorisation(email: String) = none
    def getAuthorisationList = nil
    def setAuthorisation(auth: AuthorisationRecord) = unit
    def deleteAuthorisation(email: String) = unit

    def createApiKey(newKey: ApiKey) = ()
    def getApiKeyList = nil
    def getApiKey(key: String) = None
    def getAndUpdateApiKey(key: String, counter: Option[String] = None) = None
    def getApiKeyByApplication(application: String) = None
    def deleteApiKey(key: String) = ()

    def findProjects = nil
    def writeDeploy(deploy: DeployRecordDocument) = ()
    def writeLog(log: LogDocument) = ()
    def deleteDeployLog(uuid: UUID) = ()
    def updateStatus(uuid: UUID, state: magenta.RunState) = ()
    def updateDeploySummary(uuid: UUID, totalTasks: Option[Int], completedTasks: Int, lastActivityTime: DateTime, hasWarnings: Boolean) = ()
    def addMetaData(uuid: UUID, metaData: Map[String, String]) = ()
  }

  lazy val store: DataStore = if (Config.postgres.isEnabled) postgresStore else mongoStore

  private lazy val mongoStore: DataStore = {
    val dataStore = MongoDatastore.buildDatastore().getOrElse(NoOpDataStore)
    log.info("Persistence datastore initialised as %s" format dataStore)
    dataStore
  }

  private lazy val postgresStore: DataStore = {
    PostgresDatastore.buildDatastore()
  }

}


