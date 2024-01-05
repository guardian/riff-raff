package persistence

import java.sql.{Connection, DriverManager}
import java.util.UUID

import conf.Config
import controllers.{AuthorisationRecord, Logging, SimpleDeployDetail}
import deployment.{DeployFilter, PaginationView}
import magenta.RunState
import org.apache.commons.dbcp2.{
  ConnectionFactory,
  PoolableConnectionFactory,
  PoolingDataSource
}
import org.apache.commons.pool2.impl.GenericObjectPool
import org.joda.time.{DateTime, Period}
import play.api.libs.json
import play.api.libs.json._
import scalikejdbc._
import utils.Json._

class PostgresDatastore(config: Config) extends DataStore(config) with Logging {

  private def getCollectionStats[A](
      table: SQLSyntaxSupport[A]
  ): CollectionStats =
    logExceptions(Some(s"Requestion table stats for ${table.tableName}")) {
      DB readOnly { implicit session =>
        SQL(
          s"SELECT pg_table_size('${table.tableName}'), pg_total_relation_size('${table.tableName}'), counts.cpt FROM information_schema.tables, (SELECT count(*) AS cpt FROM ${table.tableName}) as counts limit 1"
        ).map(CollectionStats(_)).single().apply()
      }
    } match {
      case Left(t) =>
        log.error(s"Unable to collect statistics for ${table.tableName}", t)
        CollectionStats.Empty
      case Right(stats) => stats.getOrElse(CollectionStats.Empty)
    }

  override def collectionStats: Map[String, CollectionStats] =
    Map(
      DeployRecordDocument.tableName -> getCollectionStats(
        DeployRecordDocument
      ),
      LogDocument.tableName -> getCollectionStats(LogDocument),
      AuthorisationRecord.tableName -> getCollectionStats(AuthorisationRecord),
    )

  // Table: auth(email: String, content: jsonb)
  def getAuthorisation(
      email: String
  ): Either[Throwable, Option[AuthorisationRecord]] =
    logExceptions(Some(s"Requesting authorisation object for $email")) {
      DB readOnly { implicit session =>
        sql"SELECT content FROM auth WHERE email = $email"
          .map(AuthorisationRecord(_))
          .single()
          .apply()
      }
    }

  def getAuthorisationList: Either[Throwable, List[AuthorisationRecord]] =
    logExceptions(Some("Requesting list of authorisation objects")) {
      DB readOnly { implicit session =>
        sql"SELECT content FROM auth".map(AuthorisationRecord(_)).list().apply()
      }
    }

  def setAuthorisation(auth: AuthorisationRecord): Either[Throwable, Unit] =
    logExceptions(Some(s"Creating auth object $auth")) {
      DB localTx { implicit session =>
        val json = Json.toJson(auth).toString()
        sql"INSERT INTO auth (email, content) VALUES (${auth.email}, $json::jsonb) ON CONFLICT (email) DO UPDATE SET content = $json::jsonb"
          .update()
          .apply()
      }
    }

  def deleteAuthorisation(email: String): Either[Throwable, Unit] =
    logExceptions(Some(s"Deleting authorisation object for $email")) {
      DB localTx { implicit session =>
        sql"DELETE FROM auth WHERE email = $email".update().apply()
      }
    }

  // Table: deploy(id: String, content: jsonb)
  override def writeDeploy(deploy: DeployRecordDocument): Unit =
    logAndSquashExceptions(
      Some(s"Saving deploy record document for ${deploy.uuid}"),
      ()
    ) {
      DB localTx { implicit session =>
        val json = Json.toJson(deploy).toString()
        sql"INSERT INTO deploy (id, content) VALUES (${deploy.uuid}, $json::jsonb) ON CONFLICT (id) DO UPDATE SET content = $json::jsonb"
          .update()
          .apply()
      }
    }

  override def readDeploy(uuid: UUID): Option[DeployRecordDocument] =
    logAndSquashExceptions[Option[DeployRecordDocument]](
      Some(s"Retrieving deploy record document for $uuid"),
      None
    ) {
      DB readOnly { implicit session =>
        sql"SELECT content FROM deploy WHERE id = $uuid"
          .map(DeployRecordDocument(_))
          .single()
          .apply()
      }
    }

  override def getDeploys(
      filter: Option[DeployFilter],
      pagination: PaginationView
  ): Either[Throwable, List[DeployRecordDocument]] =
    logExceptions(Some(s"Requesting list of deploys using filters $filter")) {
      DB readOnly { implicit session =>
        val whereFilters: SQLSyntax =
          filter.map(_.postgresFilters).getOrElse(sqls"")
        val paginationFilters = pagination.pageSize.fold(sqls"")(size =>
          sqls"OFFSET ${size * (pagination.page - 1)} LIMIT $size"
        )
        sql"SELECT content FROM deploy $whereFilters ORDER BY content->>'startTime' DESC $paginationFilters"
          .map(DeployRecordDocument(_))
          .list()
          .apply()
      }
    }

  override def updateStatus(uuid: UUID, status: RunState): Unit =
    logAndSquashExceptions(Some(s"Updating status of $uuid to $status"), ()) {
      DB localTx { implicit session =>
        val update = Json.toJson(Map("status" -> status.entryName)).toString()
        sql"UPDATE deploy SET content = content || $update::jsonb WHERE id = $uuid"
          .update()
          .apply()
      }
    }

  override def updateDeploySummary(
      uuid: UUID,
      totalTasks: Option[Int],
      completedTasks: Int,
      lastActivityTime: DateTime,
      hasWarnings: Boolean
  ): Unit = logAndSquashExceptions(Some(s"Update deploy $uuid summary"), ()) {
    DB localTx { implicit session =>
      val updatesMap: Map[String, JsValue] = Map(
        "completedTasks" -> JsNumber(completedTasks),
        "lastActivityTime" -> json.JsString(lastActivityTime.toString()),
        "hasWarnings" -> JsBoolean(hasWarnings)
      ) ++ totalTasks.map("totalTasks" -> JsNumber(_))

      val updates = Json.toJson(updatesMap).toString()
      sql"UPDATE deploy SET content = content || $updates::jsonb WHERE id = $uuid"
        .update()
        .apply()
    }
  }

  // Used in testing
  override def getDeployUUIDs(limit: Int = 0): List[SimpleDeployDetail] =
    logAndSquashExceptions(
      Some(s"Requesting deploy UUIDs"),
      List.empty[SimpleDeployDetail]
    ) {
      DB readOnly { implicit session =>
        val limitSQL = if (limit == 0) sqls"" else sqls"LIMIT $limit"
        sql"SELECT id, content->>'startTime' FROM deploy ORDER BY content.startTime $limitSQL"
          .map(SimpleDeployDetail(_))
          .list()
          .apply()
      }
    }

  override def countDeploys(filter: Option[DeployFilter]): Int =
    logAndSquashExceptions[Int](Some("Counting documents matching filter"), 0) {
      DB readOnly { implicit session =>
        val whereFilters = filter.map(_.postgresFilters).getOrElse(List.empty)
        sql"SELECT count(*) FROM deploy $whereFilters"
          .map(_.int(1))
          .single()
          .apply()
          .get
      }
    }

  override def getCompleteDeploysOlderThan(
      dateTime: DateTime
  ): List[SimpleDeployDetail] = logAndSquashExceptions(
    Some(s"Requesting completed deploys older than $dateTime"),
    List.empty[SimpleDeployDetail]
  ) {
    DB readOnly { implicit session =>
      sql"SELECT id, content->>'startTime' FROM deploy WHERE (content->>'startTime')::TIMESTAMP < $dateTime::TIMESTAMP AND (content->>'summarised') IS NOT NULL"
        .map(SimpleDeployDetail(_))
        .list()
        .apply()
    }
  }

  // Most likely not used
  override def addMetaData(uuid: UUID, metaData: Map[String, String]): Unit = {}

  override def findProjects(): Either[Throwable, List[String]] =
    logExceptions(Some("Requesting projects")) {
      DB readOnly { implicit session =>
        sql"SELECT DISTINCT content->'parameters'->>'projectName' FROM deploy"
          .map(_.string(1))
          .list()
          .apply()
      }
    }

  // TODO: Deprecate stringUUID
  override def addStringUUID(uuid: UUID): Unit = {}
  override def getDeployUUIDsWithoutStringUUIDs: List[SimpleDeployDetail] = {
    List.empty
  }

  override def getLastCompletedDeploys(projectName: String): Map[String, UUID] =
    logAndSquashExceptions(
      Some(s"Requesting last completed deploys for $projectName"),
      Map.empty[String, UUID]
    ) {
      DB readOnly { implicit session =>
        val threshold: DateTime =
          new DateTime().minus(new Period().withDays(90))

        val list =
          sql"""
             SELECT DISTINCT ON (1)
                    content->'parameters'->>'stage', (content->>'startTime')::TIMESTAMP, id
             FROM   deploy
             WHERE content->'parameters'->>'projectName'=$projectName
               AND content->>'status'='Completed'
               AND (content->>'startTime')::TIMESTAMP > $threshold::TIMESTAMP
             ORDER  BY 1, 2 DESC, 3;
           """
            .map(res => (res.string(1), UUID.fromString(res.string(3))))
            .list()
            .apply()

        list.toMap
      }
    }

  override def summariseDeploy(uuid: UUID): Unit =
    logAndSquashExceptions(Some(s"Summarising deploy $uuid"), ()) {
      DB localTx { implicit session =>
        val update = Json.toJson(Map("summarised" -> true)).toString()
        sql"UPDATE deploy SET content = content || $update::jsonb WHERE id = $uuid"
          .update()
          .apply()
        sql"DELETE FROM deployLog WHERE id = $uuid".update().apply()
      }
    }

  override def deleteDeployLog(uuid: UUID): Unit = logAndSquashExceptions(
    Some(s"Deleting deploy log for deploy with id $uuid"),
    ()
  ) {
    DB localTx { implicit session =>
      sql"DELETE FROM deploy WHERE id = $uuid".update().apply()
      sql"DELETE FROM deployLog WHERE id = $uuid".update().apply()
    }
  }

  // Table: deployLog(id: String, content: jsonb)
  override def writeLog(log: LogDocument): Unit = logAndSquashExceptions(
    Some(
      s"Writing new log document with id ${log.id} for deploy ${log.deploy}"
    ),
    ()
  ) {
    DB localTx { implicit session =>
      val json = Json.toJson(log).toString()
      sql"INSERT INTO deployLog (id, content) VALUES (${log.id}, $json::jsonb) ON CONFLICT (id) DO UPDATE SET content = $json::jsonb"
        .update()
        .apply()
    }
  }

  override def readLogs(uuid: UUID): List[LogDocument] = logAndSquashExceptions(
    Some(s"Retrieving logs for deploy $uuid"),
    List.empty[LogDocument]
  ) {
    DB readOnly { implicit session =>
      sql"SELECT content FROM deployLog WHERE content ->>'deploy' = ${uuid.toString()}"
        .map(LogDocument(_))
        .list()
        .apply()
    }
  }
}

class PostgresDatastoreOps(config: Config, passwordProvider: PasswordProvider) {
  def buildDatastore() = {
    Class.forName("org.postgresql.Driver")

    val connectionPoolDataSource = DynamicPasswordDataSourceConnectionPool(
      url = config.postgres.url,
      user = config.postgres.user,
      passwordProvider = passwordProvider
    )
    ConnectionPool.singleton(connectionPoolDataSource)

    new PostgresDatastore(config)
  }
}
