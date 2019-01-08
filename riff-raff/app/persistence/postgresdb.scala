package persistence

import java.util.UUID

import conf.Configuration
import controllers.{ApiKey, AuthorisationRecord, Logging, SimpleDeployDetail}
import deployment.{DeployFilter, PaginationView}
import magenta.RunState
import org.joda.time.{DateTime, Period}
import play.api.libs.json
import play.api.libs.json._
import scalikejdbc._
import utils.Json._

class PostgresDatastore extends DataStore with Logging {

  // Table: auth(email: String, content: jsonb)
  def getAuthorisation(email: String): Either[Throwable, Option[AuthorisationRecord]] = logExceptions(Some(s"Requesting authorisation object for $email")) {
    DB readOnly { implicit session =>
      sql"SELECT content FROM auth WHERE email = $email".map(AuthorisationRecord(_)).single.apply()
    }
  }

  def getAuthorisationList: Either[Throwable, List[AuthorisationRecord]] = logExceptions(Some("Requesting list of authorisation objects")) {
    DB readOnly { implicit session =>
      sql"SELECT content FROM auth".map(AuthorisationRecord(_)).list().apply()
    }
  }

  def setAuthorisation(auth: AuthorisationRecord): Either[Throwable, Unit] = logExceptions(Some(s"Creating auth object $auth")) {
    DB localTx { implicit session =>
      val json = Json.toJson(auth).toString()
      sql"INSERT INTO auth (email, content) VALUES (${auth.email}, $json::jsonb) ON CONFLICT (email) DO UPDATE SET content = $json::jsonb".update.apply()
    }
  }

  def deleteAuthorisation(email: String): Either[Throwable, Unit] = logExceptions(Some(s"Deleting authorisation object for $email")) {
    DB localTx { implicit session =>
      sql"DELETE FROM auth WHERE email = $email".update.apply()
    }
  }

  // Table: apiKey(id: String, content: jsonb)
  def createApiKey(newKey: ApiKey): Unit = DB localTx { implicit session =>
    val json = Json.toJson(newKey).toString()
    sql"INSERT INTO apiKey (key, content) VALUES (${newKey.key}, $json::jsonb) ON CONFLICT (key) DO UPDATE SET content = $json::jsonb".update.apply()
  }

  def getApiKeyList: Either[Throwable, List[ApiKey]] = logExceptions(Some("Requesting list of API keys")) {
    DB readOnly { implicit session =>
      sql"SELECT content FROM apiKey".map(ApiKey(_)).list().apply()
    }
  }

  def getApiKey(key: String): Option[ApiKey] = DB readOnly { implicit session =>
    sql"SELECT content FROM apiKey WHERE key = $key".map(ApiKey(_)).single.apply()
  }

  def getAndUpdateApiKey(key: String, counterOpt: Option[String]): Option[ApiKey] = DB localTx { implicit session =>
    val now = java.time.ZonedDateTime.now.toOffsetDateTime.toString

    val q: SQLSyntax = counterOpt match {
      case Some(counter) =>
        val content: String = s"""
        jsonb_set(
            content || '{"lastUsed": "$now"}',
            '{callCounters, $counter}',
            (COALESCE(content->'callCounters'->>'$counter','0')::int + 1)::text::jsonb
        )
        """
        SQLSyntax.createUnsafely(s"""
          UPDATE
              apiKey
          SET
              content = $content
          WHERE
              key = '$key';
          """)
      case None =>
        sqls"""UPDATE apiKey SET content = content || '{"lastUsed": "$now"}'::jsonb WHERE key = $key"""
    }

    sql"$q".update.apply()

    //return updated apiKey
    sql"SELECT content FROM apiKey WHERE key = $key".map(ApiKey(_)).single().apply()
  }

  def getApiKeyByApplication(application: String): Option[ApiKey] = DB readOnly { implicit session =>
    sql"SELECT content FROM apiKey WHERE content->>'application' = $application".map(ApiKey(_)).single.apply()
  }

  def deleteApiKey(key: String): Unit = DB localTx { implicit session =>
    sql"DELETE FROM apiKey WHERE key = $key".update.apply()
  }

  // Table: deploy(id: String, content: jsonb)
  override def writeDeploy(deploy: DeployRecordDocument): Unit = DB localTx { implicit session =>
    val json = Json.toJson(deploy).toString()
    sql"INSERT INTO deploy (id, content) VALUES (${deploy.uuid}, $json::jsonb) ON CONFLICT (id) DO UPDATE SET content = $json::jsonb".update.apply()
  }

  override def readDeploy(uuid: UUID): Option[DeployRecordDocument] = DB readOnly { implicit session =>
    sql"SELECT content FROM deploy WHERE id = $uuid".map(DeployRecordDocument(_)).single.apply()
  }

  override def getDeploys(filter: Option[DeployFilter], pagination: PaginationView): Either[Throwable, Iterable[DeployRecordDocument]] = DB readOnly { implicit session =>
    val whereFilters: SQLSyntax = filter.map(_.postgresFilters).getOrElse(sqls"")
    val paginationFilters = pagination.pageSize.fold(sqls"")(size => sqls"OFFSET ${size*(pagination.page-1)} LIMIT $size")
    Right(sql"SELECT content FROM deploy $whereFilters $paginationFilters".map(DeployRecordDocument(_)).list.apply())
  }

  override def updateStatus(uuid: UUID, status: RunState): Unit = DB localTx { implicit session =>
    val update = Json.toJson(Map("status" -> status.entryName)).toString()
    sql"UPDATE deploy SET content = content || $update::jsonb WHERE id = $uuid".update.apply()
  }

  override def updateDeploySummary(uuid: UUID, totalTasks: Option[Int], completedTasks: Int, lastActivityTime: DateTime, hasWarnings: Boolean): Unit = DB localTx { implicit session =>
    val updatesMap: Map[String, JsValue] = Map(
      "completedTasks" -> JsNumber(completedTasks),
      "lastActivityTime" -> json.JsString(lastActivityTime.toString()),
      "hasWarnings" -> JsBoolean(hasWarnings)) ++ totalTasks.map("totalTasks" -> JsNumber(_))

    val updates = Json.toJson(updatesMap).toString()
    sql"UPDATE deploy SET content = content || $updates::jsonb WHERE id = $uuid".update.apply()
  }

  // Used in testing
  override def getDeployUUIDs(limit: Int = 0): Iterable[SimpleDeployDetail] = DB readOnly { implicit session =>
    val limitSQL = if (limit == 0) sqls"" else sqls"LIMIT $limit"
    sql"SELECT id, content->>'startTime' FROM deploy ORDER BY content.startTime $limitSQL".map(SimpleDeployDetail(_)).single.apply()
  }

  override def countDeploys(filter: Option[DeployFilter]): Int = DB readOnly { implicit session =>
    val whereFilters = filter.map(_.postgresFilters).getOrElse(List.empty)
    sql"SELECT count(*) FROM deploy $whereFilters".map(_.int(1)).single.apply().get
  }

  override def getCompleteDeploysOlderThan(dateTime: DateTime): Iterable[SimpleDeployDetail] = DB readOnly { implicit session =>
    sql"SELECT id, content->>'startTime' FROM deploy WHERE (content->>'startTime')::TIMESTAMP < $dateTime::TIMESTAMP AND (content->>'summarised') IS NOT NULL"
      .map(SimpleDeployDetail(_)).list.apply()
  }

  // Most likely not used
  override def addMetaData(uuid: UUID, metaData: Map[String, String]): Unit = {}
//  DB localTx { implicit session =>
//    val update = metaData.map { case (tag, value) =>
//      sqls"content->>'{parameters,tags,$tag}'" -> value
//    }
//    if (update.nonEmpty) sql"UPDATE deploy SET content || $update::jsonb WHERE id = $uuid".update.apply()
//  }

  override def findProjects(): Either[Throwable, List[String]] = logExceptions(None) {
    DB readOnly { implicit session =>
      sql"SELECT DISTINCT content->'parameters'->>'projectName' FROM deploy".map(_.string(1)).list.apply()
    }
  }

  //TODO: Deprecate stringUUID
  override def addStringUUID(uuid: UUID): Unit = {}
  override def getDeployUUIDsWithoutStringUUIDs: Iterable[SimpleDeployDetail] = { List.empty }

  override def getLastCompletedDeploys(projectName: String): Map[String,UUID] = DB readOnly { implicit session =>
    val threshold: DateTime = new DateTime().minus(new Period().withDays(90))

    val list: Seq[(String, UUID)] = sql"""
      SELECT id, content->'parameters'->>'stage'
      FROM deploy
      WHERE content->'parameters'->>'projectName'=$projectName
        AND content->>'status'='Completed'
        AND (content->>'startTime')::TIMESTAMP > $threshold::TIMESTAMP
      GROUP BY content->'parameters'->>'projectName', content->'parameters'->>'stage', id
      ORDER BY content->>'startTime'
    """.map(res => (res.string(2), UUID.fromString(res.string(1)))).list.apply()

    list.toMap
  }

  override def summariseDeploy(uuid: UUID): Unit = DB localTx { implicit session =>
    val update = Json.toJson(Map("summarised" -> true)).toString()
    sql"UPDATE deploy SET content = content || $update::jsonb WHERE id = $uuid".update.apply()
    sql"DELETE FROM deployLog WHERE id = $uuid".update.apply()
  }

  override def deleteDeployLog(uuid: UUID): Unit = DB localTx { implicit session =>
    sql"DELETE FROM deploy WHERE id = $uuid".update.apply()
    sql"DELETE FROM deployLog WHERE id = $uuid".update.apply()
  }

  // Table: deployLog(id: String, content: jsonb)
  override def writeLog(log: LogDocument): Unit = DB localTx { implicit session =>
    val json = Json.toJson(log).toString()
    sql"INSERT INTO deployLog (id, content) VALUES (${log.id}, $json::jsonb) ON CONFLICT (id) DO UPDATE SET content = $json::jsonb".update.apply()
  }

  override def readLogs(uuid: UUID): Iterable[LogDocument] = DB readOnly { implicit session =>
    sql"SELECT content FROM deployLog WHERE id = $uuid".map(LogDocument(_)).list.apply()
  }
}

object PostgresDatastore {
  def buildDatastore() = {
    Class.forName("org.postgresql.Driver")
    ConnectionPool.singleton(Configuration.postgres.url, Configuration.postgres.user, Configuration.postgres.password)

    new PostgresDatastore
  }
}