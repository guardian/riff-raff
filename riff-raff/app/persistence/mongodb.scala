package persistence

import java.util.{UUID}
import com.mongodb.casbah._
import com.mongodb.casbah.Imports.WriteConcern
import conf.Configuration
import controllers.{ApiKey, AuthorisationRecord, Logging, SimpleDeployDetail}
import play.api.Application
import deployment.{PaginationView, DeployFilter}
import magenta.{Build, RunState}
import scala.Some
import com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers
import notification.{HookConfig, HookAction, HookCriteria}
import com.mongodb.casbah.commons.MongoDBObject
import org.joda.time.{Period, DateTime}
import com.mongodb.casbah.query.Imports._
import com.mongodb.util.JSON
import ci.ContinuousDeploymentConfig

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

  RegisterJodaTimeConversionHelpers()

  def buildDatastore(app:Option[Application]) = try {
    if (Configuration.mongo.isConfigured) {
      val uri = MongoClientURI(Configuration.mongo.uri.get)
      val mongoClient = MongoClient(uri)
      val db = MongoDB(mongoClient, uri.database.get)
      Some(new MongoDatastore(db, app.map(_.classloader)))
    } else None
  } catch {
    case e:Throwable =>
      log.error("Couldn't initialise MongoDB connection", e)
      None
  }
}

class MongoDatastore(database: MongoDB, val loader: Option[ClassLoader]) extends DataStore with DocumentStore with Logging {
  def getCollection(name: String) = database(s"${Configuration.mongo.collectionPrefix}$name")
  val deployCollection = getCollection("deployV2")
  val deployLogCollection = getCollection("deployV2Logs")
  val hookConfigsCollection = getCollection("hookConfigs")
  val authCollection = getCollection("auth")
  val deployJsonCollection = getCollection("deployJson")
  val apiKeyCollection = getCollection("apiKeys")
  val continuousDeployCollection = getCollection("continuousDeploy")
  val keyValuesCollection = getCollection("keyValues")

  val collections = List(deployCollection, deployLogCollection, hookConfigsCollection,
    authCollection, deployJsonCollection, apiKeyCollection, continuousDeployCollection)

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

  override def getPostDeployHook(id: UUID): Option[HookConfig] =
    logAndSquashExceptions[Option[HookConfig]](Some("Getting hook config for %s" format id), None) {
      hookConfigsCollection.findOneByID(id).flatMap(HookConfig.fromDBO(_))
    }

  override def getPostDeployHook(projectName: String, stage: String): Iterable[HookConfig] =
    logAndSquashExceptions[Iterable[HookConfig]](Some(s"Getting hook deploy configs for project $projectName and stage $stage"), Nil) {
      hookConfigsCollection.find(MongoDBObject("projectName" -> projectName, "stage" -> stage))
        .toIterable.flatMap(HookConfig.fromDBO(_))
    }

  override def getPostDeployHookList: Iterable[HookConfig] =
    logAndSquashExceptions[Iterable[HookConfig]](Some("Getting all hook deploy configs"), Nil) {
      hookConfigsCollection.find().sort(MongoDBObject("enabled" -> 1, "projectName" -> 1, "stage" -> 1))
        .toIterable.flatMap(HookConfig.fromDBO(_))
    }

  override def setPostDeployHook(config: HookConfig) {
    logAndSquashExceptions(Some(s"Saving hook deploy config: $config"),()) {
      hookConfigsCollection.findAndModify(
        query = MongoDBObject("_id" -> config.id),
        update = config.toDBO,
        upsert = true,
        fields = MongoDBObject(),
        sort = MongoDBObject(),
        remove = false,
        returnNew = false
      )
    }
  }

  override def deletePostDeployHook(id: UUID) {
    logAndSquashExceptions(Some(s"Deleting post deploy hook $id"),()) {
      hookConfigsCollection.findAndRemove(MongoDBObject("_id" -> id))
    }
  }

  override def setAuthorisation(auth: AuthorisationRecord) {
    logAndSquashExceptions(Some("Creating auth object %s" format auth),()) {
      val criteriaId = MongoDBObject("_id" -> auth.email)
      authCollection.findAndModify(
        query = criteriaId,
        update = auth.toDBO,
        upsert = true, fields = MongoDBObject(),
        sort = MongoDBObject(),
        remove = false,
        returnNew=false
      )
    }
  }

  override def getAuthorisation(email: String): Option[AuthorisationRecord] =
    logAndSquashExceptions[Option[AuthorisationRecord]](Some("Requesting authorisation object for %s" format email),None) {
      authCollection.findOneByID(email).flatMap(AuthorisationRecord.fromDBO(_))
    }

  override def getAuthorisationList: List[AuthorisationRecord] =
    logAndSquashExceptions[List[AuthorisationRecord]](Some("Requesting list of authorisation objects"), Nil) {
      authCollection.find().flatMap(AuthorisationRecord.fromDBO(_)).toList
    }

  override def deleteAuthorisation(email: String) {
    logAndSquashExceptions(Some("Deleting authorisation object for %s" format email),()) {
      authCollection.findAndRemove(MongoDBObject("_id" -> email))
    }
  }

  override def getContinuousDeployment(id: UUID): Option[ContinuousDeploymentConfig] =
    logAndSquashExceptions[Option[ContinuousDeploymentConfig]](Some("Getting continuous deploy config for %s" format id), None) {
      continuousDeployCollection.findOneByID(id).flatMap(ContinuousDeploymentConfig.fromDBO(_))
    }
  override def getContinuousDeploymentList():Iterable[ContinuousDeploymentConfig] =
    logAndSquashExceptions[Iterable[ContinuousDeploymentConfig]](Some("Getting all continuous deploy configs"), Nil) {
      continuousDeployCollection.find().sort(MongoDBObject("enabled" -> 1, "projectName" -> 1, "stage" -> 1))
        .toIterable.flatMap(ContinuousDeploymentConfig.fromDBO(_))
    }
  override def setContinuousDeployment(cd: ContinuousDeploymentConfig) {
    logAndSquashExceptions(Some("Saving continuous integration config: %s" format cd),()) {
      continuousDeployCollection.findAndModify(
        query = MongoDBObject("_id" -> cd.id),
        update = cd.toDBO,
        upsert = true,
        fields = MongoDBObject(),
        sort = MongoDBObject(),
        remove = false,
        returnNew = false
      )
    }
  }
  override def deleteContinuousDeployment(id: UUID) {
    logAndSquashExceptions(Some("Deleting continuous integration config for %s" format id),()) {
      continuousDeployCollection.findAndRemove(MongoDBObject("_id" -> id))
    }
  }

  override def createApiKey(newKey: ApiKey) {
    logAndSquashExceptions(Some("Saving new API key %s" format newKey.key),()) {
      val dbo = newKey.toDBO
      apiKeyCollection.insert(dbo)
    }
  }

  override def getApiKeyList = logAndSquashExceptions[Iterable[ApiKey]](Some("Requesting list of API keys"), Nil) {
    val keys = apiKeyCollection.find().sort(MongoDBObject("application" -> 1))
    keys.toIterable.flatMap( ApiKey.fromDBO(_) )
  }

  override def getApiKey(key: String) =
    logAndSquashExceptions[Option[ApiKey]](Some("Getting API key details for %s" format key),None) {
      apiKeyCollection.findOneByID(key).flatMap(ApiKey.fromDBO(_))
    }

  override def getAndUpdateApiKey(key: String, counter: Option[String]) = {
    val setLastUsed = $set("lastUsed" -> (new DateTime()))
    val incCounter = counter.map(name => $inc(("callCounters.%s" format name) -> 1L)).getOrElse(MongoDBObject())
    val update = setLastUsed ++ incCounter
    logAndSquashExceptions[Option[ApiKey]](Some("Getting and updating API key details for %s" format key),None) {
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

  override def deleteApiKey(key: String) {
    logAndSquashExceptions(Some("Deleting API key for %s" format key),()) {
      apiKeyCollection.findAndRemove(MongoDBObject("_id" -> key))
    }
  }

  override def writeDeploy(deploy: DeployRecordDocument) {
    logAndSquashExceptions(Some("Saving deploy record document for %s" format deploy.uuid),()) {
      val gratedDeploy = deploy.toDBO
      deployCollection.insert(gratedDeploy, WriteConcern.Safe)
    }
  }

  override def updateStatus(uuid: UUID, status: RunState.Value) {
    logAndSquashExceptions(Some("Updating status of %s to %s" format (uuid, status)), ()) {
      deployCollection.update(MongoDBObject("_id" -> uuid), $set("status" -> status.toString), concern=WriteConcern.Safe)
    }
  }

  override def updateDeploySummary(uuid: UUID, totalTasks:Option[Int], completedTasks:Int, lastActivityTime:DateTime) {
    logAndSquashExceptions(Some(s"Updating summary of $uuid to total:$totalTasks, completed:$completedTasks, lastActivivty:$lastActivityTime"), ()) {
      val fields =
        List("completedTasks" -> completedTasks, "lastActivityTime" -> lastActivityTime) ++
        totalTasks.map("totalTasks" ->)
      deployCollection.update(MongoDBObject("_id" -> uuid), $set(fields: _*), concern=WriteConcern.Safe)
    }
  }

  override def readDeploy(uuid: UUID): Option[DeployRecordDocument] =
    logAndSquashExceptions[Option[DeployRecordDocument]](Some("Retrieving deploy record document for %s" format uuid), None) {
      deployCollection.findOneByID(uuid).flatMap(DeployRecordDocument.fromDBO(_))
    }

  override def writeLog(log: LogDocument) {
    logAndSquashExceptions(Some("Writing new log document with id %s for deploy %s" format (log.id, log.deploy)),()) {
      deployLogCollection.insert(log.toDBO, WriteConcern.Safe)
    }
  }

  override def readLogs(uuid: UUID): Iterable[LogDocument] =
    logAndSquashExceptions[Iterable[LogDocument]](Some("Retriving logs for deploy %s" format uuid),Nil) {
      val criteria = MongoDBObject("deploy" -> uuid)
      deployLogCollection.find(criteria).toIterable.flatMap(LogDocument.fromDBO(_))
    }

  override def getDeployUUIDs(limit: Int = 0) = logAndSquashExceptions[Iterable[SimpleDeployDetail]](None,Nil){
    val cursor = deployCollection.find(MongoDBObject(), MongoDBObject("_id" -> 1, "startTime" -> 1)).sort(MongoDBObject("startTime" -> -1))
    val limitedCursor = if (limit == 0) cursor else cursor.limit(limit)
    limitedCursor.toIterable.map { dbo =>
      val uuid = dbo.getAs[UUID]("_id").get
      val dateTime = dbo.getAs[DateTime]("startTime")
      SimpleDeployDetail(uuid, dateTime)
    }
  }

  override def getDeploys(filter: Option[DeployFilter], pagination: PaginationView) = logAndSquashExceptions[Iterable[DeployRecordDocument]](None,Nil){
    val criteria = filter.map(_.criteria).getOrElse(MongoDBObject())
    val cursor = deployCollection.find(criteria).sort(MongoDBObject("startTime" -> -1)).pagination(pagination)
    cursor.toIterable.flatMap { DeployRecordDocument.fromDBO(_) }
  }

  override def countDeploys(filter: Option[DeployFilter]) = logAndSquashExceptions[Int](Some("Counting documents matching filter"),0) {
    val criteria = filter.map(_.criteria).getOrElse(MongoDBObject())
    deployCollection.count(criteria).toInt
  }

  override def deleteDeployLog(uuid: UUID) {
    logAndSquashExceptions(None,()) {
      deployCollection.findAndRemove(MongoDBObject("_id" -> uuid))
      deployLogCollection.remove(MongoDBObject("deploy" -> uuid))
    }
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

  override def addMetaData(uuid: UUID, metaData: Map[String, String]) {
    logAndSquashExceptions(Some("Adding metadata %s to %s" format (metaData, uuid)),()) {
      val update = metaData.map { case (tag, value) =>
        $set(("parameters.tags.%s" format tag) -> value)
      }.fold(MongoDBObject())(_ ++ _)
      if (update.size > 0)
        deployCollection.update( MongoDBObject("_id" -> uuid), update )
    }
  }

  override def summariseDeploy(uuid: UUID) {
    logAndSquashExceptions(Some("Summarising deploy %s" format uuid),()) {
      deployCollection.update( MongoDBObject("_id" -> uuid), $set("summarised" -> true))
      deployLogCollection.remove(MongoDBObject("deploy" -> uuid))
    }
  }

  override def writeDeployJson(id: Build, json: String) {
    logAndSquashExceptions(None,()) {
      val key = MongoDBObject("projectName" -> id.projectName, "buildId" -> id.id)
      deployJsonCollection.insert(MongoDBObject("_id" -> key, "json" -> JSON.parse(json)))
    }
  }

  override def getDeployJson(id: Build) = {
    logAndSquashExceptions[Option[String]](None,None) {
      val key = MongoDBObject("projectName" -> id.projectName, "buildId" -> id.id)
      deployJsonCollection.findOneByID(key).map{ result =>
        JSON.serialize(result.get("json"))
      }
    }
  }

  override def getLastCompletedDeploys(projectName: String):Map[String,UUID] = {
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

  override def writeKey(key: String, value: String) {
    logAndSquashExceptions[Unit](Some(s"Writing value $value for key $key"), ()) {
      keyValuesCollection.findAndModify(
        query = MongoDBObject("key" -> key),
        update = MongoDBObject("key" -> key, "value" -> value),
        upsert = true,
        fields = MongoDBObject(),
        sort = MongoDBObject(),
        remove = false,
        returnNew = false
      )
    }
  }

  override def readKey(key: String): Option[String] =
    logAndSquashExceptions[Option[String]](Some(s"Retrieving value for key $key"), None) {
      keyValuesCollection.findOne(MongoDBObject("key" -> key)).flatMap(_.getAs[String]("value"))
    }

  override def deleteKey(key: String) {
    logAndSquashExceptions[Unit](Some(s"Deleting key value pair for key $key"), ()) {
      keyValuesCollection.findAndRemove(MongoDBObject("key" -> key))
    }
  }

  override def findProjects(): List[String] = logAndSquashExceptions[List[String]](None,Nil) {
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