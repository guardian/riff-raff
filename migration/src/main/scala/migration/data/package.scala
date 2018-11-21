package migration

import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import java.time.Instant
import java.util.UUID
import org.mongodb.scala.{ Document => MDocument, _ }
import scalaz._, scalaz.std._, scalaz.syntax.std._, Scalaz._

package object data extends FromBsonInstances {
  ///
  /// BSON decoders
  implicit class RichDocument(val doc: MDocument) extends AnyVal {
    def getAs[A](key: String)(implicit B: FromBson[A]): Option[A] =
      doc.get(key).flatMap(B.getAs(_))
  }

  ///
  /// Mongo decoders
  implicit val apiKeyParser: FromMongo[ApiKey] = (dbo: MDocument) =>
    (
      dbo.getAs[String]("_id") |@|
      dbo.getAs[String]("application") |@|
      dbo.getAs[String]("issuedBy") |@|
      dbo.getAs[Instant]("created") |@|
      dbo.getAs[Option[Instant]]("lastUsed") |@|
      (dbo.getAs[MDocument]("callCounters").map(_.toBsonDocument) >>= FromBson[Map[String, Long]].getAs)
    )(ApiKey)

  implicit val authParser: FromMongo[Auth] = (dbo: MDocument) =>
    (
      dbo.getAs[String]("_id") |@|
      dbo.getAs[String]("approvedBy") |@|
      dbo.getAs[Instant]("approvedDate")
    )(Auth)

  implicit val deployParser: FromMongo[Deploy] = (dbo: MDocument) =>
    (
      dbo.getAs[UUID]("_id") |@|
      dbo.getAs[Option[String]]("stringUUID") |@|
      dbo.getAs[Instant]("startTime") |@|
      (dbo.getAs[MDocument]("parameters") >>= FromMongo[Parameters].parseMongo) |@|
      dbo.getAs[RunState]("status") |@|
      dbo.getAs[Option[Boolean]]("summarised") |@|
      dbo.getAs[Option[Int]]("totalTasks") |@|
      dbo.getAs[Option[Int]]("completedTasks") |@|
      dbo.getAs[Option[Instant]]("lastActivityTime") |@|
      dbo.getAs[Option[Boolean]]("hasWarnings")
    )(Deploy)

  implicit val parametersParser: FromMongo[Parameters] = (dbo: MDocument) =>
    (
      dbo.getAs[String]("deployer") |@|
      dbo.getAs[String]("projectName") |@|
      dbo.getAs[String]("buildId") |@|
      dbo.getAs[String]("stage") |@|
      dbo.getAs[Map[String, String]]("tags") |@|
      (dbo.getAs[MDocument]("selector") >>= FromMongo[DeploymentSelector].parseMongo)
    )(Parameters)

  implicit val deploySelectorParser: FromMongo[DeploymentSelector] = (dbo: MDocument) =>
    dbo.getAs[String]("_typeHint") >>= {
      case "persistence.DeploymentKeysSelectorDocument" => 
        dbo.getAs[List[MDocument]]("ids")
          .flatMap(_.traverse(FromMongo[DeploymentKey].parseMongo))
          .map(KeysSelector)
      case _ => Some(AllSelector)
    }

  implicit val deployKeyParser: FromMongo[DeploymentKey] = (dbo: MDocument) =>
    (
      dbo.getAs[String]("name") |@|
      dbo.getAs[String]("action") |@|
      dbo.getAs[String]("stack") |@|
      dbo.getAs[String]("region")
    )(DeploymentKey)

  implicit val logParser: FromMongo[Log] = (dbo: MDocument) =>
    (
      dbo.getAs[UUID]("deploy") |@|
      dbo.getAs[UUID]("id") |@|
      dbo.getAs[Option[UUID]]("parent") |@|
      (dbo.getAs[MDocument]("document") >>= FromMongo[Document].parseMongo) |@|
      dbo.getAs[Instant]("time")
    )(Log)

  implicit val documentParser: FromMongo[Document] = (dbo: MDocument) =>
    dbo.getAs[String]("_typeHint") >>= {
      case "persistence.DeployDocument"        => Some(DeployDocument)
      case "persistence.InfoDocument"          => dbo.getAs[String]("text") map InfoDocument
      case "persistence.TaskListDocument"      =>
        for {
          objs <- dbo.getAs[List[MDocument]]("taskList")
          tasks <- objs.toList.traverse(any => FromMongo[TaskDetail].parseMongo(any.asInstanceOf[MDocument]))
        } yield TaskListDocument(tasks)
      case "persistence.TaskRunDocument"       => 
        for {
          obj <- dbo.getAs[MDocument]("task")
          task <- FromMongo[TaskDetail].parseMongo(obj)
        } yield TaskRunDocument(task)
      case "persistence.CommandOutputDocument" => dbo.getAs[String]("text") map CommandOutputDocument
      case "persistence.CommandErrorDocument"  => dbo.getAs[String]("text") map CommandErrorDocument
      case "persistence.WarningDocument"       => dbo.getAs[String]("text") map WarningDocument
      case "persistence.FailDocument"          => 
        for { 
          text <- dbo.getAs[String]("text")
          detail <- dbo.getAs[MDocument]("detail")
          parsedDetail <- FromMongo[ThrowableDetail].parseMongo(detail)
        } yield FailDocument(text, parsedDetail)
      case "persistence.VerboseDocument"       => dbo.getAs[String]("text") map VerboseDocument
      case "persistence.FinishContextDocument" => Some(FinishContextDocument)
      case "persistence.FailContextDocument"   => Some(FailContextDocument)
      case _                                   => None
    }

  implicit val taskdetailParser: FromMongo[TaskDetail] = (dbo: MDocument) =>
    (
      dbo.getAs[String]("name") |@|
      dbo.getAs[String]("description") |@|
      dbo.getAs[String]("verbose")
    )(TaskDetail)
   
  implicit val throwableDetailParser: FromMongo[ThrowableDetail] = (dbo: MDocument) =>
    (
      dbo.getAs[String]("name") |@|
      dbo.getAs[String]("message") |@|
      dbo.getAs[String]("stackTrace") |@|
      (dbo.getAs[Option[MDocument]]("cause") >>= {
        case None => Some(None)
        case Some(doc) => Some(throwableDetailParser.parseMongo(doc))
      })
    )(ThrowableDetail)
  ///

  ///
  /// JSON encoders
  def remove(field: String): Json => Json = json => json.asObject.fold(json)(o => Json.fromJsonObject(o.remove(field)))

  implicit val instantEncoder          : Encoder[Instant] = Encoder[String].contramap(_.toString)
  // implicit val uuidEncoder             : Encoder[UUID] = Encoder[String].contramap(_.toString)
  val _apiKeyEncoder                   : Encoder[ApiKey] = deriveEncoder
  implicit val apiKeyEncoder           : Encoder[ApiKey] = _apiKeyEncoder.mapJson(remove("key"))
  val _authEncoder                     : Encoder[Auth] = deriveEncoder
  implicit val authEncoder             : Encoder[Auth] = _authEncoder.mapJson(remove("email"))
  implicit val deployRunState          : Encoder[RunState] = Encoder[String].contramap(_.toString)
  implicit val deployDeploymentKey     : Encoder[DeploymentKey] = deriveEncoder
  implicit val deployParameters        : Encoder[Parameters] = deriveEncoder
  val _deployEncoder                   : Encoder[Deploy] = deriveEncoder
  implicit val deployEncoder           : Encoder[Deploy] = _deployEncoder.mapJson(remove("id"))
  implicit val deployThrowableDetail   : Encoder[ThrowableDetail] = deriveEncoder
  implicit val deployTaskDetail        : Encoder[TaskDetail] = deriveEncoder
  val _deployLog                       : Encoder[Log] = deriveEncoder
  implicit val deployLogEncoder        : Encoder[Log] = _deployLog.mapJson(remove("deploy"))
  implicit val deployDeploymentSelector: Encoder[DeploymentSelector] = Encoder.instance {
    case AllSelector       => JsonObject("_typeHint" -> "persistence.AllSelector".asJson).asJson
    case KeysSelector(ids) => JsonObject("_typeHint" -> "persistence.DeploymentKeysSelectorDocument".asJson, "ids" -> ids.asJson).asJson
  }
  implicit val deployDocument          : Encoder[Document] = Encoder.instance {
    case DeployDocument => JsonObject("_typeHint" -> "persistence.DeployDocument".asJson).asJson
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
    case FinishContextDocument => JsonObject("_typeHint" -> "persistence.FinishContextDocument".asJson).asJson
    case FailContextDocument   => JsonObject("_typeHint" -> "persistence.FailContextDocument".asJson).asJson
  }
  ///

  ///
  /// Postgre encoders
  implicit val apiKeyPE: ToPostgre[ApiKey] = new ToPostgre[ApiKey] {
    type K = String
    def key(a: ApiKey) = a.key
    def json(a: ApiKey) = apiKeyEncoder(a)
  }

  implicit val authPE: ToPostgre[Auth] = new ToPostgre[Auth] {
    type K = String
    def key(a: Auth) = a.email
    def json(a: Auth) = authEncoder(a)
  }

  implicit val deployPE: ToPostgre[Deploy] = new ToPostgre[Deploy] {
    type K = UUID
    def key(a: Deploy) = a.id
    def json(a: Deploy) = deployEncoder(a)
  }

  implicit val logPE: ToPostgre[Log] = new ToPostgre[Log] {
    type K = UUID
    def key(a: Log) = a.id
    def json(a: Log) = deployLogEncoder(a)
  }
}