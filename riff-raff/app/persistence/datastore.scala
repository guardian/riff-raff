package persistence

import java.util.UUID

import conf.{Config, DatastoreMetrics}
import controllers.{ApiKey, AuthorisationRecord, Logging}
import deployment.PaginationView
import org.joda.time.DateTime
import play.api.Logger
import utils.Retriable

abstract class DataStore(config: Config) extends DocumentStore with Retriable {
  def log: Logger

  def logAndSquashExceptions[T](message: Option[String], default: T)(block: => T): T =
    logExceptions(message)(block).fold(_ => default, identity)

  def logExceptions[T](message: Option[String])(block: => T): Either[Throwable, T] =
    try {
      val result = run(block)
      message.foreach(m => log.debug(s"Completed: $m"))
      Right(result)
    } catch {
      case t: Throwable =>
        val error = "Caught exception%s" format message.map(" whilst %s" format _).getOrElse("")
        log.error(error, t)
        Left(t)
    }

  def run[T](block: => T): T = new DatastoreMetrics(config, this).DatastoreRequest.measure(block)

  def collectionStats: Map[String, CollectionStats] = Map.empty

  def getAuthorisation(email: String): Either[Throwable, Option[AuthorisationRecord]]
  def getAuthorisationList(pagination: Option[PaginationView] = None): Either[Throwable, List[AuthorisationRecord]]
  def setAuthorisation(auth: AuthorisationRecord): Either[Throwable, Unit]
  def deleteAuthorisation(email: String): Either[Throwable, Unit]

  def createApiKey(newKey: ApiKey): Unit
  def getApiKeyList(pagination: Option[PaginationView] = None): Either[Throwable, Iterable[ApiKey]]
  def getApiKey(key: String): Option[ApiKey]
  def getAndUpdateApiKey(key: String, counter: Option[String] = None): Option[ApiKey]
  def getApiKeyByApplication(application: String): Option[ApiKey]
  def deleteApiKey(key: String): Unit
}

class NoOpDataStore(config: Config) extends DataStore(config) with Logging {
  import NoOpDataStore._

  final def getAuthorisation(email: String): Either[Throwable, Option[AuthorisationRecord]] = none
  final val getAuthorisationList(pagination: Option[PaginationView] = None) = nil
  final def setAuthorisation(auth: AuthorisationRecord): Either[Throwable, Unit] = unit
  final def deleteAuthorisation(email: String): Either[Throwable, Unit] = unit

  final def createApiKey(newKey: ApiKey): Unit = ()
  final val getApiKeyList(pagination: Option[PaginationView] = None) = nil
  final def getApiKey(key: String): Option[ApiKey] = None
  final def getAndUpdateApiKey(key: String, counter: Option[String] = None): Option[ApiKey] = None
  final def getApiKeyByApplication(application: String): Option[ApiKey] = None
  final def deleteApiKey(key: String): Unit = ()

  final def findProjects = nil
  final def writeDeploy(deploy: DeployRecordDocument) = ()
  final def writeLog(log: LogDocument) = ()
  final def deleteDeployLog(uuid: UUID) = ()
  final def updateStatus(uuid: UUID, state: magenta.RunState) = ()
  final def updateDeploySummary(uuid: UUID, totalTasks: Option[Int], completedTasks: Int, lastActivityTime: DateTime, hasWarnings: Boolean) = ()
  final def addMetaData(uuid: UUID, metaData: Map[String, String]) = ()
}
object NoOpDataStore {
  private final val none = Right(None)
  private final val nil = Right(Nil)
  private final val unit = Right(())
}
