package datastore

import java.util.UUID
import deployment.Task
import lifecycle.Lifecycle
import magenta._
import com.mongodb.casbah.{MongoURI, MongoDB, MongoConnection}
import com.mongodb.casbah.Imports._
import conf.Configuration
import controllers.Logging
import com.novus.salat._
import play.Application
import play.api.Play
import play.api.Play.current
import deployment.DeployRecord
import magenta.DeployParameters
import magenta.MessageStack
import magenta.Deployer
import scala.Some
import magenta.Build
import com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers
import com.gu.management.Metric

object MongoDatastore extends Lifecycle with Logging {

  val MESSAGE_STACKS = "messageStacks"

  def buildDatastore(app:Option[Application]) = try {
    if (Configuration.mongo.isConfigured) {
      val uri = MongoURI(Configuration.mongo.uri.get)
      val mongoConn = MongoConnection(uri)
      val mongoDB = mongoConn(uri.database.get)
      if (mongoDB.authenticate(uri.username.get,new String(uri.password.get))) {
        Some(new MongoDatastore(mongoDB, app.map(_.classloader())))
      } else {
        log.error("Authentication to mongoDB failed")
        None
      }
    } else None
  } catch {
    case e:Throwable =>
      log.error("Couldn't initialise MongoDB connection", e)
      None
  }

  def init(app:Application) {
    val datastore = buildDatastore(Option(app))
    datastore.foreach(DataStore.register(_))
  }
  def shutdown(app:Application) { DataStore.unregisterAll() }

  val testUUID = UUID.randomUUID()
  val testParams = DeployParameters(Deployer("Simon Hildrew"), Build("tools::deploy", "182"), Stage("DEV"))
  val testRecord = DeployRecord(Task.Deploy, testUUID, testParams)
  val testStack1 = MessageStack(List(Deploy(testParams)))
  val testStack2 = MessageStack(List(Info("Test info message"),Deploy(testParams)))
}

trait RiffRaffGraters {
  RegisterJodaTimeConversionHelpers()
  def loader:Option[ClassLoader]
  implicit val context = {
    val context = new Context {
      val name = "global"
      override val typeHintStrategy = StringTypeHintStrategy(TypeHintFrequency.Always)
    }
    loader.foreach(context.registerClassLoader(_))
    context.registerPerClassKeyOverride(classOf[DeployRecord], remapThis = "uuid", toThisInstead = "_id")
    context
  }
  val recordGrater = grater[DeployRecord]
  val stackGrater = grater[MessageStack]
}

class MongoDatastore(database: MongoDB, val loader: Option[ClassLoader]) extends DataStore with RiffRaffGraters {
  val deployCollection = database("%sdeploys" format Configuration.mongo.collectionPrefix)

  private def stats = deployCollection.stats
  def dataSize = stats.getLong("size", 0L)
  def storageSize = stats.getLong("storageSize", 0L)
  def documentCount = deployCollection.count

  def createDeploy(record: DeployRecord) {
    val dbObject = recordGrater.asDBObject(record)
    deployCollection insert dbObject
  }
  def updateDeploy(uuid: UUID, stack: MessageStack) {
    val newMessageStack = stackGrater.asDBObject(stack)
    deployCollection.update(MongoDBObject("_id" -> uuid), $push(MongoDatastore.MESSAGE_STACKS -> newMessageStack))
  }
  def getDeploy(uuid: UUID): Option[DeployRecord] = {
    val deploy = deployCollection.findOneByID(uuid)
    deploy.map(recordGrater.asObject(_))
  }

  def getDeploys(limit: Int): Iterable[DeployRecord] = {
    val deploys = deployCollection.find().sort(MongoDBObject("time" -> -1)).limit(limit)
    deploys.toIterable.map{ deployDbObject =>
      try {
        recordGrater.asObject(deployDbObject)
      } catch {
        case t:Throwable =>
          val uuid = deployDbObject.getAs[UUID]("_id")
          throw new RuntimeException("Failed to reconstituting deploy %s" format uuid, t)
      }
    }
  }

  def getDeployUUIDs = {
    val uuidObjects = deployCollection.find(MongoDBObject(), MongoDBObject("_id" -> 1)).sort(MongoDBObject("time" -> -1))
    uuidObjects.toIterable.flatMap(_.getAs[UUID]("_id"))
  }

  def deleteDeployLog(uuid: UUID) {
    deployCollection.findAndRemove(MongoDBObject("_id" -> uuid))
  }
}