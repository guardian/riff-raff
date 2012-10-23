package datastore

import java.util.UUID
import deployment.DeployRecord
import magenta.MessageStack
import controllers.Logging
import conf.RequestMetrics.DatastoreRequest
import com.gu.management.Metric

trait DataStore {
  def dataSize:Long
  def storageSize:Long
  def documentCount:Long

  def getDeploys(limit: Int): Iterable[DeployRecord]

  def createDeploy(record:DeployRecord)
  def updateDeploy(uuid:UUID, stack: MessageStack)
  def getDeploy(uuid:UUID):Option[DeployRecord]

  def getDeployUUIDs:Iterable[UUID]
  def deleteDeployLog(uuid:UUID)
}

object DataStore extends DataStore with Logging {

  var datastore: Option[DataStore] = None

  def register(store: DataStore) {
    if (datastore.isDefined) log.warn("Datastore registration has been overwritten (was %s, now %s)" format (datastore.get,store))
    datastore = Some(store)
  }
  def unregisterAll() { datastore = None }

  def logAndSquashExceptions[T](default: => T)(block: => T): T = {
    try {
      block
    } catch {
      case t:Throwable =>
        log.error("Squashing uncaught exception", t)
        default
    }
  }

  def createDeploy(record:DeployRecord) {
    log.debug("Creating record for %s" format record)
    DatastoreRequest.measure {
      logAndSquashExceptions[Unit]() {
        datastore.foreach(_.createDeploy(record))
      }
    }
  }

  def updateDeploy(uuid: UUID, stack: MessageStack) {
    log.debug("Updating record with UUID %s with stack %s" format (uuid,stack))
    DatastoreRequest.measure {
      logAndSquashExceptions[Unit]() {
        datastore.foreach(_.updateDeploy(uuid,stack))
      }
    }
  }

  def getDeploy(uuid: UUID): Option[DeployRecord] = logAndSquashExceptions[Option[DeployRecord]](None) {
    log.debug("Requesting record with UUID %s" format uuid)
    DatastoreRequest.measure {
      datastore.flatMap(_.getDeploy(uuid))
    }
  }

  def getDeploys(limit: Int): Iterable[DeployRecord] = logAndSquashExceptions[Iterable[DeployRecord]](Nil) {
    log.debug("Requesting last %d deploys" format limit)
    DatastoreRequest.measure {
      datastore.map(_.getDeploys(limit)).getOrElse(Nil)
    }
  }

  def dataSize = DatastoreRequest.measure { datastore.map(_.dataSize).getOrElse(0) }
  def storageSize = DatastoreRequest.measure { datastore.map(_.storageSize).getOrElse(0) }
  def documentCount = DatastoreRequest.measure { datastore.map(_.documentCount).getOrElse(0) }

  def getDeployUUIDs() = DatastoreRequest.measure { datastore.map(_.getDeployUUIDs).getOrElse(Nil) }

  def deleteDeployLog(uuid: UUID) { DatastoreRequest.measure {
    datastore.foreach(_.deleteDeployLog(uuid))
  } }

}


