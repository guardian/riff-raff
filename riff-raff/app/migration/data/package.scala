package migration

import controllers.{ ApiKey, AuthorisationRecord }
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import java.nio.ByteBuffer
import org.joda.time.DateTime
import java.util.UUID
import magenta.{RunState, ThrowableDetail, TaskDetail}
import persistence._
import scala.util.Try
import cats._, cats.implicits._
import scalikejdbc._

package object data {

  ///
  /// JSON encoders
  def remove(field: String): Json => Json = json => json.asObject.fold(json)(o => Json.fromJsonObject(o.remove(field)))

  implicit val instantEncoder          : Encoder[DateTime] = Encoder[String].contramap(_.toString)
  val _apiKeyEncoder                   : Encoder[ApiKey] = deriveEncoder
  implicit val apiKeyEncoder           : Encoder[ApiKey] = _apiKeyEncoder.mapJson(remove("key"))
  val _authEncoder                     : Encoder[AuthorisationRecord] = deriveEncoder
  implicit val authEncoder             : Encoder[AuthorisationRecord] = _authEncoder.mapJson(remove("email"))
  implicit val deployRunState          : Encoder[RunState.Value] = Encoder.enumEncoder(RunState)
  implicit val deployDeploymentKey     : Encoder[DeploymentKeyDocument] = deriveEncoder
  implicit val deployDeploymentSelector: Encoder[DeploymentSelectorDocument] = Encoder.instance {
    case AllDocument       => JsonObject("_typeHint" -> "persistence.AllSelector".asJson).asJson
    case DeploymentKeysSelectorDocument(ids) => JsonObject("_typeHint" -> "persistence.DeploymentKeysSelectorDocument".asJson, "ids" -> ids.asJson).asJson
  }
  implicit val deployParameters        : Encoder[ParametersDocument] = deriveEncoder
  val _deployEncoder                   : Encoder[DeployRecordDocument] = deriveEncoder
  implicit val deployEncoder           : Encoder[DeployRecordDocument] = _deployEncoder.mapJson(remove("uuid"))
  implicit val deployThrowableDetail   : Encoder[ThrowableDetail] = deriveEncoder
  implicit val deployTaskDetail        : Encoder[TaskDetail] = deriveEncoder
  implicit val deployDocument          : Encoder[MessageDocument] = Encoder.instance {
    case DeployDocument() => JsonObject("_typeHint" -> "persistence.DeployDocument".asJson).asJson
    case InfoDocument(text) => JsonObject("_typeHint" -> "persistence.InfoDocument".asJson, "text" -> text.asJson).asJson
    case TaskListDocument(tasks) => JsonObject(
      "_typeHint" -> "persistence.TaskListDocument".asJson,
      "taskList" -> tasks.asJson
    ).asJson
    case TaskRunDocument(task) => JsonObject(
      "_typeHint" -> "persistence.TaskRunDocument".asJson,
      "task" -> task.asJson
    ).asJson
    case CommandOutputDocument(text) => JsonObject("_typeHint" -> "persistence.CommandOutputDocument".asJson, "text" -> text.asJson).asJson
    case CommandErrorDocument(text)  => JsonObject("_typeHint" -> "persistence.CommandErrorDocument".asJson, "text" -> text.asJson).asJson
    case WarningDocument(text)       => JsonObject("_typeHint" -> "persistence.WarningDocument".asJson, "text" -> text.asJson).asJson
    case FailDocument(text, taskDetail) => JsonObject(
      "_typeHint" -> "persistence.FailDocument".asJson,
      "text" -> text.asJson,
      "detail" -> taskDetail.asJson
    ).asJson
    case VerboseDocument(text) => JsonObject("_typeHint" -> "persistence.VerboseDocument".asJson, "text" -> text.asJson).asJson
    case FinishContextDocument() => JsonObject("_typeHint" -> "persistence.FinishContextDocument".asJson).asJson
    case FailContextDocument() => JsonObject("_typeHint" -> "persistence.FailContextDocument".asJson).asJson
  }
  val _deployLog                       : Encoder[LogDocument] = deriveEncoder
  implicit val deployLogEncoder        : Encoder[LogDocument] = _deployLog.mapJson(remove("deploy"))
  // ///

  // ///
  // /// Postgre encoders
  implicit val apiKeyPE: ToPostgres[ApiKey] = new ToPostgres[ApiKey] {
    type K = String
    def key(a: ApiKey) = a.key
    def json(a: ApiKey) = apiKeyEncoder(a)
    val tableName = "apiKey"
    val id = "id"
    val idType = ColString(32, false)
    val drop = sql"DROP TABLE IF EXISTS apiKey"
    val create = sql"CREATE TABLE apiKey (id varchar(32) PRIMARY KEY, content jsonb NOT NULL)"
    def insert(key: K, json: String): SQL[Nothing, NoExtractor] =
      sql"INSERT INTO apiKey VALUES ($key, $json::jsonb) ON CONFLICT (id) DO UPDATE SET content = $json::jsonb"
  }

  implicit val authPE: ToPostgres[AuthorisationRecord] = new ToPostgres[AuthorisationRecord] {
    type K = String
    def key(a: AuthorisationRecord) = a.email
    def json(a: AuthorisationRecord) = authEncoder(a)
    val tableName = "auth"
    val id = "email"
    val idType = ColString(100, true)
    val drop = sql"DROP TABLE IF EXISTS auth"
    val create = sql"CREATE TABLE auth (email varchar(100) PRIMARY KEY, content jsonb NOT NULL)"
    def insert(key: K, json: String): SQL[Nothing, NoExtractor] =
      sql"INSERT INTO auth VALUES ($key, $json::jsonb) ON CONFLICT (email) DO UPDATE SET content = $json::jsonb"
  }

  implicit val deployPE: ToPostgres[DeployRecordDocument] = new ToPostgres[DeployRecordDocument] {
    type K = UUID
    def key(a: DeployRecordDocument) = a.uuid
    def json(a: DeployRecordDocument) = deployEncoder(a)
    val tableName = "deploy"
    val id = "id"
    val idType = ColUUID
    val drop = sql"DROP TABLE IF EXISTS deploy"
    val create = sql"CREATE TABLE deploy (id uuid PRIMARY KEY, content jsonb NOT NULL)"
    def insert(key: K, json: String): SQL[Nothing, NoExtractor] =
      sql"INSERT INTO deploy VALUES ($key::uuid, $json::jsonb) ON CONFLICT (id) DO UPDATE SET content = $json::jsonb"
  }

  implicit val logPE: ToPostgres[LogDocument] = new ToPostgres[LogDocument] {
    type K = UUID
    def key(a: LogDocument) = a.id
    def json(a: LogDocument) = deployLogEncoder(a)
    val tableName = "deployLog"
    val id = "id"
    val idType = ColUUID
    val drop = sql"DROP TABLE IF EXISTS deployLog"
    val create = sql"CREATE TABLE deployLog (id uuid PRIMARY KEY, content jsonb NOT NULL)"
    def insert(key: K, json: String): SQL[Nothing, NoExtractor] =
      sql"INSERT INTO deployLog VALUES ($key::uuid, $json::jsonb) ON CONFLICT (id) DO UPDATE SET content = $json::jsonb"
  }
}