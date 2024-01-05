package persistence

import conf.Config
import controllers.{AuthorisationRecord, Logging}
import org.joda.time.DateTime
import play.api.Logger
import scalikejdbc._
import utils.Retriable

import java.util.UUID

trait CollectionStats {
  def dataSize: Long
  def storageSize: Long
  def documentCount: Long
}

object CollectionStats extends SQLSyntaxSupport[CollectionStats] {
  val Empty = new CollectionStats {
    val dataSize = 0L
    val storageSize = 0L
    val documentCount = 0L
  }

  def apply(rs: WrappedResultSet) =
    new CollectionStats {
      val dataSize = rs.long(1)
      val storageSize = rs.long(2)
      val documentCount = rs.long(3)
    }
}

abstract class DataStore(config: Config) extends DocumentStore with Retriable {
  def log: Logger

  def logAndSquashExceptions[T](message: Option[String], default: T)(
      block: => T
  ): T =
    logExceptions(message)(block).fold(_ => default, identity)

  def logExceptions[T](
      message: Option[String]
  )(block: => T): Either[Throwable, T] =
    try {
      val result = run(block)
      message.foreach(m => log.debug(s"Completed: $m"))
      Right(result)
    } catch {
      case t: Throwable =>
        val error =
          s"Caught exception ${message.map(m => s"whilst $m").getOrElse("")}"
        log.error(error, t)
        Left(t)
    }

  def run[T](block: => T): T = block

  def collectionStats: Map[String, CollectionStats] = Map.empty

  def getAuthorisation(
      email: String
  ): Either[Throwable, Option[AuthorisationRecord]]
  def getAuthorisationList: Either[Throwable, List[AuthorisationRecord]]
  def setAuthorisation(auth: AuthorisationRecord): Either[Throwable, Unit]
  def deleteAuthorisation(email: String): Either[Throwable, Unit]
}

class NoOpDataStore(config: Config) extends DataStore(config) with Logging {
  import NoOpDataStore._

  final def getAuthorisation(
      email: String
  ): Either[Throwable, Option[AuthorisationRecord]] = none
  final def getAuthorisationList = nil
  final def setAuthorisation(
      auth: AuthorisationRecord
  ): Either[Throwable, Unit] = unit
  final def deleteAuthorisation(email: String): Either[Throwable, Unit] = unit

  final def findProjects = nil
  final def writeDeploy(deploy: DeployRecordDocument) = ()
  final def writeLog(log: LogDocument) = ()
  final def deleteDeployLog(uuid: UUID) = ()
  final def updateStatus(uuid: UUID, state: magenta.RunState) = ()
  final def updateDeploySummary(
      uuid: UUID,
      totalTasks: Option[Int],
      completedTasks: Int,
      lastActivityTime: DateTime,
      hasWarnings: Boolean
  ) = ()
  final def addMetaData(uuid: UUID, metaData: Map[String, String]) = ()
}
object NoOpDataStore {
  private final val none = Right(None)
  private final val nil = Right(Nil)
  private final val unit = Right(())
}
