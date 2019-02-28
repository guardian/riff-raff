package migration

import controllers.{ ApiKey, AuthorisationRecord }
import java.nio.ByteBuffer
import org.joda.time.DateTime
import java.util.UUID
import magenta.{RunState, ThrowableDetail, TaskDetail}
import persistence._
import scala.util.Try
import cats._, cats.implicits._
import play.api.libs.json.Json
import scalikejdbc._

package object data {

  // ///
  // /// Postgre encoders
  implicit val apiKeyPE: ToPostgres[ApiKey] = new ToPostgres[ApiKey] {
    type K = String
    def key(a: ApiKey) = a.key
    def json(a: ApiKey) = Json.toJson(a)
    val tableName = "apiKey"
    val id = "id"
    val idType = ColString(32, false)
    val drop = sql"DROP TABLE IF EXISTS apiKey"
    def create = 
      DB localTx { implicit session =>
        sql"CREATE TABLE apiKey (key varchar(32) PRIMARY KEY, content jsonb NOT NULL)"
        sql"CREATE INDEX ON apiKey ((content ->> 'application'))"
      }
    def insert(key: K, json: String): SQL[Nothing, NoExtractor] =
      sql"INSERT INTO apiKey VALUES ($key, $json::jsonb) ON CONFLICT (key) DO UPDATE SET content = $json::jsonb"
  }

  implicit val authPE: ToPostgres[AuthorisationRecord] = new ToPostgres[AuthorisationRecord] {
    type K = String
    def key(a: AuthorisationRecord) = a.email
    def json(a: AuthorisationRecord) = Json.toJson(a)
    val tableName = "auth"
    val id = "email"
    val idType = ColString(100, true)
    val drop = sql"DROP TABLE IF EXISTS auth"
    def create = 
      DB autoCommit { implicit session =>
        sql"CREATE TABLE auth (email varchar(100) PRIMARY KEY, content jsonb NOT NULL)"
      }
    def insert(key: K, json: String): SQL[Nothing, NoExtractor] =
      sql"INSERT INTO auth VALUES ($key, $json::jsonb) ON CONFLICT (email) DO UPDATE SET content = $json::jsonb"
  }

  implicit val deployPE: ToPostgres[DeployRecordDocument] = new ToPostgres[DeployRecordDocument] {
    type K = UUID
    def key(a: DeployRecordDocument) = a.uuid
    def json(a: DeployRecordDocument) = Json.toJson(a)
    val tableName = "deploy"
    val id = "id"
    val idType = ColUUID
    val drop = sql"DROP TABLE IF EXISTS deploy"
    def create = 
      DB localTx { implicit session =>
        sql"CREATE TABLE deploy (id uuid PRIMARY KEY, content jsonb NOT NULL)"
        sql"CREATE INDEX ON deploy ((content ->> 'startTime'))"
        sql"CREATE INDEX ON deploy ((content ->> 'status'))"
        sql"CREATE INDEX ON deploy ((content -> 'parameters' ->> 'projectName'))"
      }
    
    def insert(key: K, json: String): SQL[Nothing, NoExtractor] =
      sql"INSERT INTO deploy VALUES ($key::uuid, $json::jsonb) ON CONFLICT (id) DO UPDATE SET content = $json::jsonb"
  }

  implicit val logPE: ToPostgres[LogDocument] = new ToPostgres[LogDocument] {
    type K = UUID
    def key(a: LogDocument) = a.id
    def json(a: LogDocument) = Json.toJson(a)
    val tableName = "deployLog"
    val id = "id"
    val idType = ColUUID
    val drop = sql"DROP TABLE IF EXISTS deployLog"
    def create = 
      DB localTx { implicit session =>
        sql"CREATE TABLE deployLog (id uuid PRIMARY KEY, content jsonb NOT NULL)"
        sql"CREATE INDEX ON deployLog ((content ->> 'time'))"
        sql"CREATE INDEX ON deployLog ((content ->> 'deploy'))"
      }

    def insert(key: K, json: String): SQL[Nothing, NoExtractor] =
      sql"INSERT INTO deployLog VALUES ($key::uuid, $json::jsonb) ON CONFLICT (id) DO UPDATE SET content = $json::jsonb"
  }
}