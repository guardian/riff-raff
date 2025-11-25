package persistence

import java.util.UUID

import magenta.ContextMessage._
import magenta.Message._
import magenta._
import org.joda.time.DateTime
import persistence.ParametersDocument._
import play.api.libs.json._
import scalikejdbc._
import utils.Json._

case class DeployRecordDocument(
    uuid: UUID,
    stringUUID: Option[String],
    startTime: DateTime,
    parameters: ParametersDocument,
    status: RunState,
    summarised: Option[Boolean] = None,
    totalTasks: Option[Int] = None,
    completedTasks: Option[Int] = None,
    lastActivityTime: Option[DateTime] = None,
    hasWarnings: Option[Boolean] = None
)

object DeployRecordDocument extends SQLSyntaxSupport[DeployRecordDocument] {
  implicit def formats: Format[DeployRecordDocument] =
    Json.format[DeployRecordDocument]

  def apply(res: WrappedResultSet): DeployRecordDocument =
    Json.parse(res.string(1)).as[DeployRecordDocument]

  def apply(
      uuid: String,
      startTime: DateTime,
      parameters: ParametersDocument,
      status: String
  ): DeployRecordDocument = {
    DeployRecordDocument(
      UUID.fromString(uuid),
      Some(uuid),
      startTime,
      parameters,
      RunState.withName(status)
    )
  }

  override val tableName = "deploy"
}

sealed trait DeploymentSelectorDocument
object DeploymentSelectorDocument {
  implicit def formats = new Format[DeploymentSelectorDocument] {
    override def writes(doc: DeploymentSelectorDocument): JsValue = doc match {
      case AllDocument =>
        JsObject(List("_typeHint" -> JsString(AllDocument.getClass.getName)))
      case dsd @ DeploymentKeysSelectorDocument(_) =>
        Json.toJson(dsd).as[JsObject] + ("_typeHint" -> JsString(
          DeploymentKeysSelectorDocument.getClass.getName
        ))
      case dsd =>
        logger.debug(
          s"Don't know how to write DeploymentSelectorDocument of type $dsd"
        )
        JsNull
    }

    override def reads(json: JsValue): JsResult[DeploymentSelectorDocument] =
      (json \ "_typeHint").as[String].replace("$", "") match {
        case "persistence.AllDocument" => JsSuccess(AllDocument)
        case "persistence.DeploymentKeysSelectorDocument" =>
          JsSuccess(json.as[DeploymentKeysSelectorDocument])
        case hint =>
          logger.debug(
            s"Don't know how to construct DeploymentSelectorDocument of type $hint"
          )
          JsSuccess(AllDocument)
      }
  }
}

case class ParametersDocument(
    deployer: String,
    projectName: String,
    buildId: String,
    stage: String,
    tags: Map[String, String],
    selector: DeploymentSelectorDocument,
    updateStrategy: Option[Strategy]
)

object ParametersDocument {
  implicit def formats: Format[ParametersDocument] =
    Json.format[ParametersDocument]
}

sealed trait MessageDocument {
  def asMessage(
      params: DeployParameters,
      originalMessage: Option[Message] = None
  ): Message
}
object MessageDocument {

  implicit def formats = new Format[MessageDocument] {
    override def writes(doc: MessageDocument): JsValue = doc match {
      case DeployDocument =>
        JsObject(List("_typeHint" -> JsString(DeployDocument.getClass.getName)))
      case d @ TaskListDocument(_) =>
        Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(
          TaskListDocument.getClass.getName
        ))
      case d @ TaskRunDocument(_) =>
        Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(
          TaskRunDocument.getClass.getName
        ))
      case d @ InfoDocument(_) =>
        Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(
          InfoDocument.getClass.getName
        ))
      case d @ CommandOutputDocument(_) =>
        Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(
          CommandOutputDocument.getClass.getName
        ))
      case d @ CommandErrorDocument(_) =>
        Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(
          CommandErrorDocument.getClass.getName
        ))
      case d @ VerboseDocument(_) =>
        Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(
          VerboseDocument.getClass.getName
        ))
      case d @ FailDocument(_, _) =>
        Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(
          FailDocument.getClass.getName
        ))
      case FinishContextDocument =>
        JsObject(
          List("_typeHint" -> JsString(FinishContextDocument.getClass.getName))
        )
      case FailContextDocument =>
        JsObject(
          List("_typeHint" -> JsString(FailContextDocument.getClass.getName))
        )
      case d @ WarningDocument(_) =>
        Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(
          WarningDocument.getClass.getName
        ))
      case d =>
        throw new IllegalArgumentException(
          s"Don't know how to write MessageDocument of type $d"
        )
    }

    override def reads(json: JsValue): JsResult[MessageDocument] =
      (json \ "_typeHint").as[String].replace("$", "") match {
        case "persistence.DeployDocument"   => JsSuccess(DeployDocument)
        case "persistence.TaskListDocument" =>
          JsSuccess(json.as[TaskListDocument])
        case "persistence.TaskRunDocument" =>
          JsSuccess(json.as[TaskRunDocument])
        case "persistence.InfoDocument" => JsSuccess(json.as[InfoDocument])
        case "persistence.CommandOutputDocument" =>
          JsSuccess(json.as[CommandOutputDocument])
        case "persistence.CommandErrorDocument" =>
          JsSuccess(json.as[CommandErrorDocument])
        case "persistence.VerboseDocument" =>
          JsSuccess(json.as[VerboseDocument])
        case "persistence.FailDocument" => JsSuccess(json.as[FailDocument])
        case "persistence.FinishContextDocument" =>
          JsSuccess(FinishContextDocument)
        case "persistence.FailContextDocument" => JsSuccess(FailContextDocument)
        case "persistence.WarningDocument"     =>
          JsSuccess(json.as[WarningDocument])
        case hint =>
          throw new IllegalArgumentException(
            s"Don't know how to construct MessageDocument of type $hint"
          )
      }
  }

  def apply(from: Message): MessageDocument = {
    from match {
      case Deploy(_)           => DeployDocument
      case TaskList(taskList)  => TaskListDocument(taskList)
      case TaskRun(task)       => TaskRunDocument(task)
      case Info(text)          => InfoDocument(text)
      case CommandOutput(text) => CommandOutputDocument(text)
      case CommandError(text)  => CommandErrorDocument(text)
      case Verbose(text)       => VerboseDocument(text)
      case Fail(text, detail)  => FailDocument(text, detail)
      case FinishContext(_)    => FinishContextDocument
      case FailContext(_)      => FailContextDocument
      case Warning(text)       => WarningDocument(text)
      case StartContext(_)     =>
        throw new IllegalArgumentException(
          "StartContext can not be turned into a MessageDocument"
        )
    }
  }
}

case object DeployDocument extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) =
    Deploy(params)
}

case class TaskListDocument(taskList: List[TaskDetail])
    extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) =
    TaskList(taskList)
}
object TaskListDocument {
  implicit def formats: Format[TaskListDocument] = Json.format[TaskListDocument]
}

case class TaskRunDocument(task: TaskDetail) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) =
    TaskRun(task)
}
object TaskRunDocument {
  implicit def formats: Format[TaskRunDocument] = Json.format[TaskRunDocument]
}

case class InfoDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) =
    Info(text)
}
object InfoDocument {
  implicit def formats: Format[InfoDocument] = Json.format[InfoDocument]
}

case class CommandOutputDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) =
    CommandOutput(text)
}
object CommandOutputDocument {
  implicit def formats: Format[CommandOutputDocument] =
    Json.format[CommandOutputDocument]
}

case class CommandErrorDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) =
    CommandError(text)
}
object CommandErrorDocument {
  implicit def formats: Format[CommandErrorDocument] =
    Json.format[CommandErrorDocument]
}

case class VerboseDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) =
    Verbose(text)
}
object VerboseDocument {
  implicit def formats: Format[VerboseDocument] = Json.format[VerboseDocument]
}

case class WarningDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) =
    Warning(text)
}
object WarningDocument {
  implicit def formats: Format[WarningDocument] = Json.format[WarningDocument]
}

case class FailDocument(text: String, detail: ThrowableDetail)
    extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) =
    Fail(text, detail)
}
object FailDocument {
  implicit def formats: Format[FailDocument] = Json.format[FailDocument]
}

case object FinishContextDocument extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) =
    FinishContext(originalMessage.get)
}

case object FailContextDocument extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) =
    FailContext(originalMessage.get)
}

case class LogDocument(
    deploy: UUID,
    id: UUID,
    parent: Option[UUID],
    document: MessageDocument,
    time: DateTime
)

object LogDocument extends SQLSyntaxSupport[LogDocument] {
  implicit def formats: Format[LogDocument] = Json.format[LogDocument]

  def apply(res: WrappedResultSet): LogDocument =
    Json.parse(res.string(1)).as[LogDocument]

  def apply(wrapper: MessageWrapper): LogDocument = {
    LogDocument(
      wrapper.context.deployId,
      wrapper.messageId,
      wrapper.context.parentId,
      wrapper.stack.top,
      wrapper.stack.time
    )
  }
  def apply(
      deploy: UUID,
      id: UUID,
      parent: Option[UUID],
      document: Message,
      time: DateTime
  ): LogDocument = {
    val messageDocument = document match {
      case StartContext(message) => message
      case other                 => other
    }
    LogDocument(deploy, id, parent, messageDocument.asMessageDocument, time)
  }

  override val tableName = "deploylog"
}

case class LogDocumentTree(documents: Seq[LogDocument]) {
  lazy val idMap = documents.map(log => log.id -> log).toMap
  lazy val parentMap = documents
    .filter(_.parent.isDefined)
    .groupBy(_.parent.get)
    .withDefaultValue(Nil)

  lazy val roots = documents.filter(_.parent.isEmpty)

  def parentOf(child: LogDocument): Option[LogDocument] =
    child.parent.flatMap(idMap.get)
  def childrenOf(parent: LogDocument): Seq[LogDocument] =
    parentMap(parent.id).sortBy(_.time.getMillis)

  def traverseTree(root: LogDocument): Seq[LogDocument] = {
    root :: childrenOf(root).flatMap(traverseTree).toList
  }
}

case class DeploymentKeyDocument(
    name: String,
    action: String,
    stack: String,
    region: String
)

object DeploymentKeyDocument {
  implicit def formats: Format[DeploymentKeyDocument] =
    Json.format[DeploymentKeyDocument]
}

case object AllDocument extends DeploymentSelectorDocument

case class DeploymentKeysSelectorDocument(ids: List[DeploymentKeyDocument])
    extends DeploymentSelectorDocument
object DeploymentKeysSelectorDocument {
  implicit def formats: Format[DeploymentKeysSelectorDocument] =
    Json.format[DeploymentKeysSelectorDocument]
}
