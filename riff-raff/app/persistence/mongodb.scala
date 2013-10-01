package persistence

import java.util.{UUID}
import com.mongodb.casbah.{MongoCollection, MongoDB, MongoURI, MongoConnection}
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
import org.joda.time.DateTime
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
      val uri = MongoURI(Configuration.mongo.uri.get)
      val mongoConn = MongoConnection(uri)
      val mongoDB = mongoConn(uri.database.get)

      if ( uri.username.isEmpty || uri.password.isEmpty ||
             mongoDB.authenticate(uri.username.get,new String(uri.password.get)) ) {
        Some(new MongoDatastore(mongoDB, app.map(_.classloader)))
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
}

class MongoDatastore(database: MongoDB, val loader: Option[ClassLoader]) extends DataStore with DocumentStore with Logging {
  val deployV2Collection = database("%sdeployV2" format Configuration.mongo.collectionPrefix)
  val deployV2LogCollection = database("%sdeployV2Logs" format Configuration.mongo.collectionPrefix)
  val hooksCollection = database("%shooks" format Configuration.mongo.collectionPrefix)
  val hookConfigsCollection = database("%shookConfigs" format Configuration.mongo.collectionPrefix)
  val authCollection = database("%sauth" format Configuration.mongo.collectionPrefix)
  val deployJsonCollection = database("%sdeployJson" format Configuration.mongo.collectionPrefix)
  val apiKeyCollection = database("%sapiKeys" format Configuration.mongo.collectionPrefix)
  val continuousDeployCollection = database("%scontinuousDeploy" format Configuration.mongo.collectionPrefix)

  val collections = List(deployV2Collection, deployV2LogCollection, hooksCollection,
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
  deployV2Collection.ensureIndex("startTime")
  deployV2LogCollection.ensureIndex("deploy")
  apiKeyCollection.ensureIndex(MongoDBObject("application" -> 1), "uniqueApplicationIndex", true)

  override def getPostDeployHooks = hooksCollection.find().flatMap{ dbo =>
    val criteria = HookCriteria.fromDBO(dbo.as[DBObject]("_id"))
    val action = HookAction.fromDBO(dbo)
    criteria.flatMap(c => action.map(c ->))
  }.toMap

  override def getPostDeployHook(criteria: HookCriteria) =
    logAndSquashExceptions[Option[HookAction]](Some("Requesting post deploy hook for %s" format criteria),None) {
      hooksCollection.findOneByID(criteria.toDBO).flatMap(dbo => HookAction.fromDBO(dbo))
    }

  override def setPostDeployHook(criteria: HookCriteria, action: HookAction) {
    logAndSquashExceptions(Some("Creating post deploy hook %s" format criteria),()) {
      val criteriaId = MongoDBObject("_id" -> criteria.toDBO)
      hooksCollection.findAndModify(
        query = criteriaId,
        update = criteriaId ++ action.toDBO,
        upsert = true, fields = MongoDBObject(),
        sort = MongoDBObject(),
        remove = false,
        returnNew=false
      )
    }
  }

  override def deletePostDeployHook(criteria: HookCriteria) {
    logAndSquashExceptions(Some("Deleting post deploy hook %s" format criteria),()) {
      hooksCollection.findAndRemove(MongoDBObject("_id" -> criteria.toDBO))
    }
  }


  override def getPostDeployHook(id: UUID): Option[HookConfig] =
    logAndSquashExceptions[Option[HookConfig]](Some("Getting hook config for %s" format id), None) {
      hookConfigsCollection.findOneByID(id).flatMap(HookConfig.fromDBO(_))
    }

  override def getPostDeployHook(projectName: String, stage: String): Iterable[HookConfig] =
    logAndSquashExceptions[Iterable[HookConfig]](Some(s"Getting hook deploy configs for project $projectName and stage $stage"), Nil) {
      hookConfigsCollection.find().sort(MongoDBObject("enabled" -> 1, "projectName" -> 1, "stage" -> 1))
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
  override def getContinuousDeploymentList:Iterable[ContinuousDeploymentConfig] =
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
      deployV2Collection.insert(gratedDeploy, WriteConcern.Safe)
    }
  }

  override def updateStatus(uuid: UUID, status: RunState.Value) {
    logAndSquashExceptions(Some("Updating status of %s to %s" format (uuid, status)), ()) {
      deployV2Collection.update(MongoDBObject("_id" -> uuid), $set("status" -> status.toString), concern=WriteConcern.Safe)
    }
  }

  override def readDeploy(uuid: UUID): Option[DeployRecordDocument] =
    logAndSquashExceptions[Option[DeployRecordDocument]](Some("Retrieving deploy record document for %s" format uuid), None) {
      deployV2Collection.findOneByID(uuid).flatMap(DeployRecordDocument.fromDBO(_))
    }

  override def writeLog(log: LogDocument) {
    logAndSquashExceptions(Some("Writing new log document with id %s for deploy %s" format (log.id, log.deploy)),()) {
      deployV2LogCollection.insert(log.toDBO, WriteConcern.Safe)
    }
  }

  override def readLogs(uuid: UUID): Iterable[LogDocument] =
    logAndSquashExceptions[Iterable[LogDocument]](Some("Retriving logs for deploy %s" format uuid),Nil) {
      val criteria = MongoDBObject("deploy" -> uuid)
      deployV2LogCollection.find(criteria).toIterable.flatMap(LogDocument.fromDBO(_))
    }

  override def getDeployV2UUIDs(limit: Int = 0) = logAndSquashExceptions[Iterable[SimpleDeployDetail]](None,Nil){
    val cursor = deployV2Collection.find(MongoDBObject(), MongoDBObject("_id" -> 1, "startTime" -> 1)).sort(MongoDBObject("startTime" -> -1))
    val limitedCursor = if (limit == 0) cursor else cursor.limit(limit)
    limitedCursor.toIterable.map { dbo =>
      val uuid = dbo.getAs[UUID]("_id").get
      val dateTime = dbo.getAs[DateTime]("startTime")
      SimpleDeployDetail(uuid, dateTime)
    }
  }

  override def getDeploysV2(filter: Option[DeployFilter], pagination: PaginationView) = logAndSquashExceptions[Iterable[DeployRecordDocument]](None,Nil){
    val criteria = filter.map(_.criteria).getOrElse(MongoDBObject())
    val cursor = deployV2Collection.find(criteria).sort(MongoDBObject("startTime" -> -1)).pagination(pagination)
    cursor.toIterable.flatMap { DeployRecordDocument.fromDBO(_) }
  }

  override def countDeploysV2(filter: Option[DeployFilter]) = logAndSquashExceptions[Int](Some("Counting documents matching filter"),0) {
    val criteria = filter.map(_.criteria).getOrElse(MongoDBObject())
    deployV2Collection.count(criteria).toInt
  }

  override def deleteDeployLogV2(uuid: UUID) {
    logAndSquashExceptions(None,()) {
      deployV2Collection.findAndRemove(MongoDBObject("_id" -> uuid))
      deployV2LogCollection.remove(MongoDBObject("deploy" -> uuid))
    }
  }

  override def getCompleteDeploysOlderThan(dateTime: DateTime) = logAndSquashExceptions[Iterable[SimpleDeployDetail]](None,Nil){
    deployV2Collection.find(
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
        deployV2Collection.update( MongoDBObject("_id" -> uuid), update )
    }
  }

  override def summariseDeploy(uuid: UUID) {
    logAndSquashExceptions(Some("Summarising deploy %s" format uuid),()) {
      deployV2Collection.update( MongoDBObject("_id" -> uuid), $set("summarised" -> true))
      deployV2LogCollection.remove(MongoDBObject("deploy" -> uuid))
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

  override def getLastCompletedDeploy(projectName: String):Map[String,UUID] = {
    val pipeBuilder = MongoDBList.newBuilder
    pipeBuilder += MongoDBObject("$match" ->
      MongoDBObject(
        "parameters.projectName" -> projectName,
        "parameters.deployType" -> "Deploy",
        "status" -> "Completed"
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
    log.debug("Aggregate query: %s" format pipeline.toString)
    val result = database.command(MongoDBObject("aggregate" -> deployV2Collection.name, "pipeline" -> pipeline))
    val ok = result.as[Double]("ok")
    ok match {
      case 1.0 =>
        log.debug("Result of aggregate query: %s" format result.toString)
        result.get("result") match {
          case results: BasicDBList => results.map { result =>
            result match {
              case dbo:BasicDBObject =>
                dbo.as[BasicDBObject]("_id").as[String]("stage") -> UUID.fromString(dbo.as[String]("uuid"))
            }
          }.toMap
          case _ =>
            throw new IllegalArgumentException("Mongo query did not return valid result")
        }
      case 0.0 =>
        val errorMessage = result.as[String]("errmsg")
        throw new IllegalArgumentException("Failed to execute mongo query: %s" format errorMessage)
    }
  }

  override def findProjects(): List[String] = logAndSquashExceptions[List[String]](None,Nil) {
    deployV2Collection.distinct("parameters.projectName").map(_.asInstanceOf[String]).toList
  }

  override def addStringUUID(uuid: UUID) {
    val setStringUUID = $set("stringUUID" -> uuid.toString)
    logAndSquashExceptions(Some("Updating stringUUID for %s" format uuid),()) {
      deployV2Collection.findAndModify(
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

  override def getDeployV2UUIDsWithoutStringUUIDs = logAndSquashExceptions[Iterable[SimpleDeployDetail]](None,Nil){
    val cursor = deployV2Collection.find(MongoDBObject("stringUUID" -> MongoDBObject("$exists" -> false)), MongoDBObject("_id" -> 1, "startTime" -> 1)).sort(MongoDBObject("startTime" -> -1))
    cursor.toIterable.map { dbo =>
      val uuid = dbo.getAs[UUID]("_id").get
      val dateTime = dbo.getAs[DateTime]("startTime")
      SimpleDeployDetail(uuid, dateTime)
    }
  }

}