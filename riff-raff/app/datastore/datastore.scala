package datastore

import collection.mutable
import java.util.UUID
import deployment.DeployRecord
import magenta.MessageStack
import controllers.Logging

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

  def createDeploy(record:DeployRecord) {
    datastore.foreach(_.createDeploy(record))
  }

  def updateDeploy(uuid: UUID, stack: MessageStack) {
    datastore.foreach(_.updateDeploy(uuid,stack))
  }

  def getDeploy(uuid: UUID): Option[DeployRecord] = datastore.flatMap(_.getDeploy(uuid))

  def getDeploys(limit: Int): Iterable[DeployRecord] = datastore.map(_.getDeploys(limit)).getOrElse(Nil)
}


