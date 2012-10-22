package datastore

import java.util.UUID
import deployment.DeployRecord
import magenta.MessageStack
import controllers.Logging
import conf.RequestMetrics.DatastoreRequest

trait DataStore {
  def getDeploys(limit: Int): Iterable[DeployRecord]

  def createDeploy(record:DeployRecord)
  def updateDeploy(uuid:UUID, stack: MessageStack)
  def getDeploy(uuid:UUID):Option[DeployRecord]
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
    DatastoreRequest.measure {
      logAndSquashExceptions[Unit]() {
        datastore.foreach(_.createDeploy(record))
      }
    }
  }

  def updateDeploy(uuid: UUID, stack: MessageStack) {
    DatastoreRequest.measure {
      logAndSquashExceptions[Unit]() {
        datastore.foreach(_.updateDeploy(uuid,stack))
      }
    }
  }

  def getDeploy(uuid: UUID): Option[DeployRecord] = logAndSquashExceptions[Option[DeployRecord]](None) {
    DatastoreRequest.measure {
      datastore.flatMap(_.getDeploy(uuid))
    }
  }

  def getDeploys(limit: Int): Iterable[DeployRecord] = logAndSquashExceptions[Iterable[DeployRecord]](Nil) {
    DatastoreRequest.measure {
      datastore.map(_.getDeploys(limit)).getOrElse(Nil)
    }
  }
}


