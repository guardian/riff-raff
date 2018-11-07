package persistence

import java.util.UUID

import cats.syntax.either._
import com.mongodb.casbah.Imports.WriteConcern
import com.mongodb.casbah._
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers
import com.mongodb.casbah.query.Imports._
import conf.Config
import controllers.{ApiKey, AuthorisationRecord, Logging, SimpleDeployDetail}
import deployment.{DeployFilter, PaginationView}
import magenta.RunState
import org.joda.time.{DateTime, Period}

trait MongoSerialisable[A] {

  def fromDBO(dbo: MongoDBObject)(implicit format: MongoFormat[A]): Option[A] =
    format fromDBO dbo

  implicit class MongoOps[A](a: A) {
    def toDBO(implicit format: MongoFormat[A]): DBObject = format toDBO a
  }
}

trait MongoFormat[A] {
  def toDBO(a: A): DBObject
  def fromDBO(dbo: MongoDBObject): Option[A]
}

trait CollectionStats {
  def dataSize: Long
  def storageSize: Long
  def documentCount: Long
}

object MongoDatastore extends Logging {

  val MESSAGE_STACKS = "messageStacks"

  val MAX_RETRIES = 3

  RegisterJodaTimeConversionHelpers()

  def buildDatastore() = try {
    if (Config.mongo.isConfigured) {
      val uri = MongoClientURI(Config.mongo.uri.get)
      val mongoClient = MongoClient(uri)
      val db = MongoDB(mongoClient, uri.database.get)
      Some(new MongoDatastore(db))
    } else None
  } catch {
    case e:Throwable =>
      log.error("Couldn't initialise MongoDB connection", e)
      None
  }
}

class MongoDatastore(database: MongoDB) extends DataStore with DocumentStore with Logging {
  import MongoDatastore.MAX_RETRIES

  def getCollection(name: String) = database(s"${Config.mongo.collectionPrefix}$name")
  val deployCollection = getCollection("deployV2")
  val deployLogCollection = getCollection("deployV2Logs")
  val authCollection = getCollection("auth")
  val apiKeyCollection = getCollection("apiKeys")

  val collections = List(deployCollection, deployLogCollection, authCollection, apiKeyCollection)

  private def collectionStats(collection: MongoCollection): CollectionStats = {
    val stats = collection.stats
    new CollectionStats {
      def dataSize = logAndSquashExceptions(None,0L){ stats.getLong("size", 0L) }
      def storageSize = logAndSquashExceptions(None,0L){ stats.getLong("storageSize", 0L) }
      def documentCount = logAndSquashExceptions(None,0L){ collection.getCount() }
    }
  }

  override def collectionStats: Map[String, CollectionStats] = collections.map(coll => (coll.name, collectionStats(coll))).toMap

  // ensure indexes
  deployCollection.createIndex("startTime")
  deployLogCollection.createIndex("deploy")
  apiKeyCollection.createIndex(MongoDBObject("application" -> 1), "uniqueApplicationIndex", true)

  override def setAuthorisation(auth: AuthorisationRecord) =
    logExceptions(Some("Creating auth object %s" format auth)) {
      val criteriaId = MongoDBObject("_id" -> auth.email)
      authCollection.findAndModify(
        query = criteriaId,
        update = auth.toDBO,
        upsert = true, fields = MongoDBObject(),
        sort = MongoDBObject(),
        remove = false,
        returnNew = false
      )
    }

  override def getAuthorisation(email: String) =
    logExceptions(Some("Requesting authorisation object for %s" format email)) {
      authCollection.findOneByID(email).flatMap(AuthorisationRecord.fromDBO(_))
    }

  override def getAuthorisationList =
    logExceptions(Some("Requesting list of authorisation objects")) {
      authCollection.find().flatMap(AuthorisationRecord.fromDBO(_)).toList
    }

  override def deleteAuthorisation(email: String) =
    logExceptions(Some("Deleting authorisation object for %s" format email)) {
      authCollection.findAndRemove(MongoDBObject("_id" -> email))
    }

  override def createApiKey(newKey: ApiKey) =
    retryUpTo(MAX_RETRIES, Some("Saving new API key %s" format newKey.key)) {
      val dbo = newKey.toDBO
      apiKeyCollection.insert(dbo)
    }

  override def getApiKeyList = 
    logExceptions(Some("Requesting list of API keys")) {
      val keys = apiKeyCollection.find().sort(MongoDBObject("application" -> 1))
      keys.toIterable.flatMap( ApiKey.fromDBO(_) )
    }

  override def getApiKey(key: String) =
    logAndSquashExceptions[Option[ApiKey]](Some("Getting API key details for %s" format key),None) {
      apiKeyCollection.findOneByID(key).flatMap(ApiKey.fromDBO(_))
    }

  override def getAndUpdateApiKey(key: String, counter: Option[String]) = {
    val setLastUsed = $set("lastUsed" -> new DateTime())
    val incCounter = counter.map(name => $inc(("callCounters.%s" format name) -> 1L)).getOrElse(MongoDBObject())
    val update = setLastUsed ++ incCounter
    logAndSquashExceptions[Option[ApiKey]](Some("Getting and updating API key details for %s" format key), None) {
      apiKeyCollection.findAndModify(
        query = MongoDBObject("_id" -> key),
        fields = MongoDBObject(),
        sort = MongoDBObject(),
        remove = false,
        update = update,
        returnNew = true,
        upsert = false
      ).flatMap(ApiKey.fromDBO(_))
    }
  }

  override def getApiKeyByApplication(application: String) =
    logAndSquashExceptions[Option[ApiKey]](Some("Getting API key details for application %s" format application),None) {
      apiKeyCollection.findOne(MongoDBObject("application" -> application)).flatMap(ApiKey.fromDBO(_))
    }

  override def deleteApiKey(key: String) =
    retryUpTo(MAX_RETRIES, Some("Deleting API key for %s" format key)) {
      apiKeyCollection.findAndRemove(MongoDBObject("_id" -> key))
    }

  override def writeDeploy(deploy: DeployRecordDocument) = {
    val gratedDeploy = deploy.toDBO
    retryUpTo(MAX_RETRIES, Some("Saving deploy record document for %s" format deploy.uuid)) {
      deployCollection.insert(gratedDeploy, WriteConcern.Safe)
    }
  }


  override def updateStatus(uuid: UUID, status: RunState) =
    retryUpTo(MAX_RETRIES, Some("Updating status of %s to %s" format (uuid, status))) {
      deployCollection.update(MongoDBObject("_id" -> uuid), $set("status" -> status.toString), concern=WriteConcern.Safe)
    }

  override def updateDeploySummary(uuid: UUID, totalTasks:Option[Int], completedTasks:Int, lastActivityTime:DateTime, hasWarnings:Boolean) = {
    val fields =
      List("completedTasks" -> completedTasks, "lastActivityTime" -> lastActivityTime, "hasWarnings" -> hasWarnings) ++
          totalTasks.map("totalTasks" ->)
    retryUpTo(MAX_RETRIES, Some(s"Updating summary of $uuid to total:$totalTasks, completed:$completedTasks, lastActivity:$lastActivityTime, hasWarnings:$hasWarnings")) {
      deployCollection.update(MongoDBObject("_id" -> uuid), $set(fields: _*), concern=WriteConcern.Safe)
    }
  }

  override def readDeploy(uuid: UUID): Option[DeployRecordDocument] =
    logAndSquashExceptions[Option[DeployRecordDocument]](Some("Retrieving deploy record document for %s" format uuid), None) {
      deployCollection.findOneByID(uuid).flatMap(DeployRecordDocument.fromDBO(_))
    }

  override def writeLog(log: LogDocument) =
    retryUpTo(MAX_RETRIES, Some("Writing new log document with id %s for deploy %s" format (log.id, log.deploy))) {
      deployLogCollection.insert(log.toDBO, WriteConcern.Safe)
    }

  override def readLogs(uuid: UUID): Iterable[LogDocument] =
    logAndSquashExceptions[Iterable[LogDocument]](Some("Retrieving logs for deploy %s" format uuid),Nil) {
      val criteria = MongoDBObject("deploy" -> uuid)
      deployLogCollection.find(criteria).toIterable.flatMap(LogDocument.fromDBO(_))
    }

  override def getDeployUUIDs(limit: Int = 0) = logAndSquashExceptions[Iterable[SimpleDeployDetail]](None,Nil) {
    val cursor = deployCollection.find(MongoDBObject(), MongoDBObject("_id" -> 1, "startTime" -> 1)).sort(MongoDBObject("startTime" -> -1))
    val limitedCursor = if (limit == 0) cursor else cursor.limit(limit)
    limitedCursor.toIterable.map { dbo =>
      val uuid = dbo.getAs[UUID]("_id").get
      val dateTime = dbo.getAs[DateTime]("startTime")
      SimpleDeployDetail(uuid, dateTime)
    }
  }

  override def getDeploys(filter: Option[DeployFilter], pagination: PaginationView): Either[Throwable, Iterable[DeployRecordDocument]] = Either.catchNonFatal {
    val criteria = filter.map(_.criteria).getOrElse(MongoDBObject())
    val cursor = deployCollection.find(criteria).sort(MongoDBObject("startTime" -> -1)).pagination(pagination)
    cursor.toIterable.flatMap { DeployRecordDocument.fromDBO(_) }
  }

  override def countDeploys(filter: Option[DeployFilter]) = logAndSquashExceptions[Int](Some("Counting documents matching filter"),0) {
    val criteria = filter.map(_.criteria).getOrElse(MongoDBObject())
    deployCollection.count(criteria)
  }

  override def deleteDeployLog(uuid: UUID) = {
    retryUpTo(MAX_RETRIES) { deployCollection.findAndRemove(MongoDBObject("_id" -> uuid)) }
    retryUpTo(MAX_RETRIES) { deployLogCollection.remove(MongoDBObject("deploy" -> uuid)) }
  }

  override def getCompleteDeploysOlderThan(dateTime: DateTime) = logAndSquashExceptions[Iterable[SimpleDeployDetail]](None,Nil){
    deployCollection.find(
      MongoDBObject(
        "startTime" -> MongoDBObject("$lt" -> dateTime),
        "summarised" -> MongoDBObject("$ne" -> true)
      )
    ).toIterable.map { dbo =>
      val uuid = dbo.getAs[UUID]("_id").get
      val dateTime = dbo.getAs[DateTime]("startTime")
      SimpleDeployDetail(uuid, dateTime)
    }
  }

  override def addMetaData(uuid: UUID, metaData: Map[String, String]) =  {
    logAndSquashExceptions(Some("Adding metadata %s to %s" format (metaData, uuid)),()) {
      val update = metaData.map { case (tag, value) =>
        $set(("parameters.tags.%s" format tag) -> value)
      }.fold(MongoDBObject())(_ ++ _)
      if (update.nonEmpty)
        deployCollection.update( MongoDBObject("_id" -> uuid), update )
      }
  }

  override def summariseDeploy(uuid: UUID) {
    logAndSquashExceptions(Some("Summarising deploy %s" format uuid),()) {
      deployCollection.update(MongoDBObject("_id" -> uuid), $set("summarised" -> true))
      deployLogCollection.remove(MongoDBObject("deploy" -> uuid))
    }
  }

  override def getLastCompletedDeploys(projectName: String): Map[String,UUID] = {
    val threshold = new DateTime().minus(new Period().withDays(90))
    val pipeBuilder = MongoDBList.newBuilder
    pipeBuilder += MongoDBObject("$match" ->
      MongoDBObject(
        "parameters.projectName" -> projectName,
        "status" -> "Completed",
        "startTime" -> MongoDBObject("$gte" -> threshold)
      )
    )
    pipeBuilder += MongoDBObject("$sort" -> MongoDBObject("startTime" -> 1))
    pipeBuilder += MongoDBObject("$group" ->
      MongoDBObject(
        "_id" -> MongoDBObject("projectName" -> "$parameters.projectName", "stage" -> "$parameters.stage"),
        "uuid" -> MongoDBObject("$last" -> "$stringUUID")
      )
    )
    val pipeline = pipeBuilder.result()
    log.debug(s"Aggregate query: ${pipeline.toString()}")
    val result = database.command(MongoDBObject("aggregate" -> deployCollection.name, "pipeline" -> pipeline))
    val ok = result.as[Double]("ok")
    ok match {
      case 1.0 =>
        log.debug(s"Result of aggregate query: ${result.toString}")
        result.get("result") match {
          case results: BasicDBList => results.map {
            case dbo: BasicDBObject =>
              dbo.as[BasicDBObject]("_id").as[String]("stage") -> UUID.fromString(dbo.as[String]("uuid"))
          }.toMap
          case _ =>
            throw new IllegalArgumentException("Mongo query did not return valid result")
        }
      case 0.0 =>
        val errorMessage = result.as[String]("errmsg")
        throw new IllegalArgumentException(s"Failed to execute mongo query: $errorMessage")
    }
  }

  override def findProjects: Either[Throwable, List[String]] = logExceptions(None) {
    deployCollection.distinct("parameters.projectName").map(_.asInstanceOf[String]).toList
  }

  override def addStringUUID(uuid: UUID) {
    val setStringUUID = $set("stringUUID" -> uuid.toString)
    logAndSquashExceptions(Some("Updating stringUUID for %s" format uuid),()) {
      deployCollection.findAndModify(
        query = MongoDBObject("_id" -> uuid),
        fields = MongoDBObject(),
        sort = MongoDBObject(),
        remove = false,
        update = setStringUUID,
        returnNew = false,
        upsert = false
      )
    }
  }

  override def getDeployUUIDsWithoutStringUUIDs = logAndSquashExceptions[Iterable[SimpleDeployDetail]](None,Nil){
    val cursor = deployCollection.find(MongoDBObject("stringUUID" -> MongoDBObject("$exists" -> false)), MongoDBObject("_id" -> 1, "startTime" -> 1)).sort(MongoDBObject("startTime" -> -1))
    cursor.toIterable.map { dbo =>
      val uuid = dbo.getAs[UUID]("_id").get
      val dateTime = dbo.getAs[DateTime]("startTime")
      SimpleDeployDetail(uuid, dateTime)
    }
  }

}