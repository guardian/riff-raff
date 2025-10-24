package persistence

import conf.Config
import controllers.ApiKey
import play.api.Logger
import scalikejdbc._
import utils.Retriable

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

  def createApiKey(newKey: ApiKey): Unit
  def getApiKeyList: Either[Throwable, Iterable[ApiKey]]
  def getApiKey(key: String): Option[ApiKey]
  def getAndUpdateApiKey(
      key: String,
      counter: Option[String] = None
  ): Option[ApiKey]
  def getApiKeyByApplication(application: String): Option[ApiKey]
  def deleteApiKey(key: String): Unit
}
