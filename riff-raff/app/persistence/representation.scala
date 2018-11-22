package persistence

import java.util.UUID

import com.mongodb.DBObject
import com.mongodb.casbah.commons.Implicits._
import com.mongodb.casbah.commons.{MongoDBList, MongoDBObject}
import enumeratum._
import magenta.ContextMessage._
import magenta.Message._
import magenta._
import org.joda.time.DateTime
import persistence.DetailConversions.{taskDetail, throwableDetail}
import persistence.ParametersDocument._
import play.api.libs.json._
import scalikejdbc.WrappedResultSet
import utils.Json._

case class DeployRecordDocument(uuid: UUID,
                                stringUUID: Option[String],
                                startTime: DateTime,
                                parameters: ParametersDocument,
                                status: RunState,
                                summarised: Option[Boolean] = None,
                                totalTasks: Option[Int] = None,
                                completedTasks: Option[Int] = None,
                                lastActivityTime: Option[DateTime] = None,
                                hasWarnings: Option[Boolean] = None)

object DeployRecordDocument extends MongoSerialisable[DeployRecordDocument] {
  implicit def formats: Format[DeployRecordDocument] = Json.format[DeployRecordDocument]

  def apply(res: WrappedResultSet): DeployRecordDocument = Json.parse(res.string(1)).as[DeployRecordDocument]

  def apply(uuid:String, startTime: DateTime, parameters: ParametersDocument, status: String): DeployRecordDocument = {
    DeployRecordDocument(UUID.fromString(uuid), Some(uuid), startTime, parameters, RunState.withName(status))
  }

  implicit val deployFormat: MongoFormat[DeployRecordDocument] = new DeployMongoFormat
  private class DeployMongoFormat extends MongoFormat[DeployRecordDocument] {
    def toDBO(a: DeployRecordDocument) = {
      val fields:List[(String,Any)] =
        List(
          "_id" -> a.uuid,
          "startTime" -> a.startTime,
          "parameters" -> a.parameters.toDBO,
          "status" -> a.status.toString
        ) ++ a.stringUUID.map("stringUUID" ->) ++ a.summarised.map("summarised" -> _) ++
             a.totalTasks.map("totalTasks" ->) ++ a.completedTasks.map("completedTasks" ->) ++
             a.lastActivityTime.map("lastActivityTime" ->) ++ a.hasWarnings.map("hasWarnings" ->)
      fields.toMap
    }
    def fromDBO(dbo: MongoDBObject) =
      ParametersDocument.fromDBO(dbo.as[DBObject]("parameters")).map(pd =>
        DeployRecordDocument(
        uuid = dbo.as[UUID]("_id"),
        stringUUID = dbo.getAs[String]("stringUUID"),
        startTime = dbo.as[DateTime]("startTime"),
        parameters = pd,
        status = RunState.withName(dbo.as[String]("status")),
        summarised = dbo.getAs[Boolean]("summarised"),
        totalTasks = dbo.getAs[Int]("totalTasks"),
        completedTasks = dbo.getAs[Int]("completedTasks"),
        lastActivityTime = dbo.getAs[DateTime]("lastActivityTime"),
        hasWarnings = dbo.getAs[Boolean]("hasWarnings")
      )
    )
  }
}

sealed trait DeploymentSelectorDocument {
  def asDBObject: DBObject = {
    val fields: List[(String,Any)] = List("_typeHint" -> getClass.getName) ++ dboFields
    fields.toMap
  }
  def dboFields: List[(String,Any)]
}
object DeploymentSelectorDocument {
  implicit def formats = new Format[DeploymentSelectorDocument] {
    override def writes(doc: DeploymentSelectorDocument): JsValue = doc match {
      case AllDocument => JsObject(List("_typeHint" -> JsString(AllDocument.getClass.getName)))
      case dsd@DeploymentKeysSelectorDocument(_) => Json.toJson(dsd).as[JsObject] + ("_typeHint" -> JsString(DeploymentKeysSelectorDocument.getClass.getName))
      case dsd => throw new IllegalArgumentException(s"Don't know how to write DeploymentSelectorDocument of type $dsd}")
    }


    override def reads(json: JsValue): JsResult[DeploymentSelectorDocument] = (json \ "_typeHint").as[String] match {
      case "persistence.AllDocument$" => JsSuccess(AllDocument)
      case "persistence.DeploymentKeysSelectorDocument$" => JsSuccess(json.as[DeploymentKeysSelectorDocument])
      case hint => throw new IllegalArgumentException(s"Don't know how to construct DeploymentSelectorDocument of type $hint}")
    }
  }

  def from(dbo: DBObject): DeploymentSelectorDocument = {
    dbo.as[String]("_typeHint") match {
      case "persistence.AllDocument$" => AllDocument
      case "persistence.DeploymentKeysSelectorDocument" => DeploymentKeysSelectorDocument(
        dbo.as[List[DBObject]]("keys").flatMap(dbo => DeploymentKeyDocument.fromDBO(dbo))
      )
    }
  }
}

case class ParametersDocument(
  deployer: String,
  projectName: String,
  buildId: String,
  stage: String,
  tags: Map[String,String],
  selector: DeploymentSelectorDocument
)

object ParametersDocument extends MongoSerialisable[ParametersDocument] {
  implicit def formats: Format[ParametersDocument] = Json.format[ParametersDocument]

  implicit val parametersFormat:MongoFormat[ParametersDocument] = new ParameterMongoFormat
  private class ParameterMongoFormat extends MongoFormat[ParametersDocument] {
    def toDBO(a: ParametersDocument) = {
      val fields:List[(String,Any)] =
        List(
          "deployer" -> a.deployer,
          "projectName" -> a.projectName,
          "buildId" -> a.buildId,
          "stage" -> a.stage,
          "tags" -> a.tags,
          "selector" -> a.selector.asDBObject
        )
      fields.toMap
    }
    def fromDBO(dbo: MongoDBObject) = Some(ParametersDocument(
      deployer = dbo.as[String]("deployer"),
      projectName = dbo.as[String]("projectName"),
      buildId = dbo.as[String]("buildId"),
      stage = dbo.as[String]("stage"),
      tags = dbo.as[DBObject]("tags").map(entry => (entry._1, entry._2.asInstanceOf[String])).toMap,
      selector = dbo.getAs[DBObject]("selector").map(DeploymentSelectorDocument.from).getOrElse(AllDocument)
    ))
  }
}

sealed trait MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message] = None): Message
  def asDBObject:DBObject = {
    val fields: List[(String,Any)] =
      List("_typeHint" -> getClass.getName) ++ dboFields
    fields.toMap
  }
  def dboFields: List[(String,Any)] = Nil
}
object MessageDocument {

  implicit def formats = new Format[MessageDocument] {
    override def writes(doc: MessageDocument): JsValue = doc match {
      case DeployDocument => JsObject(List("_typeHint" -> JsString(DeployDocument.getClass.getName)))
      case d@TaskListDocument(_) => Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(TaskListDocument.getClass.getName))
      case d@TaskRunDocument(_) => Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(TaskRunDocument.getClass.getName))
      case d@InfoDocument(_) => Json.toJson(d).as[JsObject] + ("_type   Hint" -> JsString(InfoDocument.getClass.getName))
      case d@CommandOutputDocument(_) => Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(CommandOutputDocument.getClass.getName))
      case d@CommandErrorDocument(_) => Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(CommandErrorDocument.getClass.getName))
      case d@VerboseDocument(_) => Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(VerboseDocument.getClass.getName))
      case d@FailDocument(_, _) => Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(FailDocument.getClass.getName))
      case FinishContextDocument => JsObject(List("_typeHint" -> JsString(FinishContextDocument.getClass.getName)))
      case FailContextDocument => JsObject(List("_typeHint" -> JsString(FailContextDocument.getClass.getName)))
      case d@WarningDocument(_) => Json.toJson(d).as[JsObject] + ("_typeHint" -> JsString(WarningDocument.getClass.getName))
      case d => throw new IllegalArgumentException(s"Don't know how to write MessageDocument of type $d}")
    }


    override def reads(json: JsValue): JsResult[MessageDocument] = (json \ "_typeHint").as[String] match {
      case "persistence.DeployDocument$" => JsSuccess(DeployDocument)
      case "persistence.TaskListDocument$" => JsSuccess(json.as[TaskListDocument])
      case "persistence.TaskRunDocument$" => JsSuccess(json.as[TaskRunDocument])
      case "persistence.InfoDocument$" => JsSuccess(json.as[InfoDocument])
      case "persistence.CommandOutputDocument$" => JsSuccess(json.as[CommandOutputDocument])
      case "persistence.CommandErrorDocument$" => JsSuccess(json.as[CommandErrorDocument])
      case "persistence.VerboseDocument$" => JsSuccess(json.as[VerboseDocument])
      case "persistence.FailDocument$" => JsSuccess(json.as[FailDocument])
      case "persistence.FinishContextDocument$" => JsSuccess(FinishContextDocument)
      case "persistence.FailContextDocument$" => JsSuccess(FailContextDocument)
      case "persistence.WarningDocument$" => JsSuccess(json.as[WarningDocument])
      case hint => throw new IllegalArgumentException(s"Don't know how to construct MessageDocument of type $hint}")
    }
  }

  def apply(from: Message): MessageDocument = {
    from match {
      case Deploy(_) => DeployDocument
      case TaskList(taskList) => TaskListDocument(taskList)
      case TaskRun(task) => TaskRunDocument(task)
      case Info(text) => InfoDocument(text)
      case CommandOutput(text) => CommandOutputDocument(text)
      case CommandError(text) => CommandErrorDocument(text)
      case Verbose(text) => VerboseDocument(text)
      case Fail(text, detail) => FailDocument(text, detail)
      case FinishContext(_) => FinishContextDocument
      case FailContext(_) => FailContextDocument
      case Warning(text) => WarningDocument(text)
      case StartContext(_) => throw new IllegalArgumentException("StartContext can not be turned into a MessageDocument")
    }
  }

  def from(dbo: DBObject): MessageDocument = {
    import DetailConversions._
    dbo.as[String]("_typeHint") match {
      case "persistence.DeployDocument$" => DeployDocument
      case "persistence.TaskListDocument" => TaskListDocument(dbo.as[MongoDBList]("taskList").flatMap(dbo => taskDetail.fromDBO(dbo.asInstanceOf[DBObject])).toList)
      case "persistence.TaskRunDocument" => TaskRunDocument(taskDetail.fromDBO(dbo.as[DBObject]("task")).get)
      case "persistence.InfoDocument" => InfoDocument(dbo.as[String]("text"))
      case "persistence.CommandOutputDocument" => CommandOutputDocument(dbo.as[String]("text"))
      case "persistence.CommandErrorDocument" => CommandErrorDocument(dbo.as[String]("text"))
      case "persistence.VerboseDocument" => VerboseDocument(dbo.as[String]("text"))
      case "persistence.FailDocument" => FailDocument(dbo.as[String]("text"), throwableDetail.fromDBO(dbo.as[DBObject]("detail")).get)
      case "persistence.FinishContextDocument$" => FinishContextDocument
      case "persistence.FailContextDocument$" => FailContextDocument
      case "persistence.WarningDocument" => WarningDocument(dbo.as[String]("text"))
      case hint => throw new IllegalArgumentException(s"Don't know how to construct MessageDocument of type $hint}")
    }
  }
}


case object DeployDocument extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = Deploy(params)
}

case class TaskListDocument(taskList: List[TaskDetail]) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = TaskList(taskList)
  override lazy val dboFields = List("taskList" -> taskList.map(DetailConversions.taskDetail.toDBO))
}
object TaskListDocument {
  implicit def formats: Format[TaskListDocument] = Json.format[TaskListDocument]
}

case class TaskRunDocument(task: TaskDetail) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = TaskRun(task)
  override lazy val dboFields = List("task" -> DetailConversions.taskDetail.toDBO(task))
}
object TaskRunDocument {
  implicit def formats: Format[TaskRunDocument] = Json.format[TaskRunDocument]
}

case class InfoDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = Info(text)
  override lazy val dboFields = List("text" -> text)
}
object InfoDocument {
  implicit def formats: Format[InfoDocument] = Json.format[InfoDocument]
}

case class CommandOutputDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = CommandOutput(text)
  override lazy val dboFields = List("text" -> text)
}
object CommandOutputDocument {
  implicit def formats: Format[CommandOutputDocument] = Json.format[CommandOutputDocument]
}

case class CommandErrorDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = CommandError(text)
  override lazy val dboFields = List("text" -> text)
}
object CommandErrorDocument {
  implicit def formats: Format[CommandErrorDocument] = Json.format[CommandErrorDocument]
}

case class VerboseDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = Verbose(text)
  override lazy val dboFields = List("text" -> text)
}
object VerboseDocument {
  implicit def formats: Format[VerboseDocument] = Json.format[VerboseDocument]
}

case class WarningDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = Warning(text)
  override lazy val dboFields = List("text" -> text)
}
object WarningDocument {
  implicit def formats: Format[WarningDocument] = Json.format[WarningDocument]
}

case class FailDocument(text: String, detail: ThrowableDetail) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = Fail(text, detail)
  override lazy val dboFields = List("text" -> text, "detail" -> DetailConversions.throwableDetail.toDBO(detail))
}
object FailDocument {
  implicit def formats: Format[FailDocument] = Json.format[FailDocument]
}

case object FinishContextDocument extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = FinishContext(originalMessage.get)
}

case object FailContextDocument extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = FailContext(originalMessage.get)
}


case class LogDocument(
  deploy: UUID,
  id: UUID,
  parent: Option[UUID],
  document: MessageDocument,
  time: DateTime
)

object LogDocument extends MongoSerialisable[LogDocument] {
  implicit def formats: Format[LogDocument] = Json.format[LogDocument]

  def apply(res: WrappedResultSet): LogDocument = Json.parse(res.string(1)).as[LogDocument]

  def apply(wrapper: MessageWrapper): LogDocument = {
    LogDocument(wrapper.context.deployId, wrapper.messageId, wrapper.context.parentId, wrapper.stack.top, wrapper.stack.time)
  }
  def apply(deploy: UUID, id: UUID, parent: Option[UUID], document: Message, time: DateTime): LogDocument = {
    val messageDocument = document match {
        case StartContext(message) => message
        case other => other
      }
    LogDocument(deploy, id, parent, messageDocument.asMessageDocument, time)
  }

  implicit val logFormat: MongoFormat[LogDocument] = new LogMongoFormat
  private class LogMongoFormat extends MongoFormat[LogDocument] {
    def toDBO(a: LogDocument) = {
      val fields:List[(String,Any)] =
        List(
          "deploy" -> a.deploy,
          "id" -> a.id,
          "document" -> a.document.asDBObject,
          "time" -> a.time
        ) ++ a.parent.map("parent" -> _)
      fields.toMap
    }

    def fromDBO(dbo: MongoDBObject) = Some(LogDocument(
      deploy = dbo.as[UUID]("deploy"),
      id = dbo.as[UUID]("id"),
      parent = dbo.getAs[UUID]("parent"),
      document = MessageDocument.from(dbo.as[DBObject]("document")),
      time = dbo.as[DateTime]("time")
    ))
  }
}

case class LogDocumentTree(documents: Seq[LogDocument]) {
  lazy val idMap = documents.map(log => log.id-> log).toMap
  lazy val parentMap = documents.filter(_.parent.isDefined).groupBy(_.parent.get).withDefaultValue(Nil)

  lazy val roots = documents.filter(_.parent.isEmpty)

  def parentOf(child: LogDocument): Option[LogDocument] = child.parent.flatMap(idMap.get)
  def childrenOf(parent: LogDocument): Seq[LogDocument] = parentMap(parent.id).sortBy(_.time.getMillis)

  def traverseTree(root: LogDocument): Seq[LogDocument] = {
    root :: childrenOf(root).flatMap(traverseTree).toList
  }
}

object DetailConversions {
  val taskDetail = new MongoFormat[TaskDetail] {
    def toDBO(a: TaskDetail) = {
      val fields:List[(String,Any)] =
        List(
          "name" -> a.name,
          "description" -> a.description,
          "verbose" -> a.verbose
        )
      fields.toMap
    }

    def fromDBO(dbo: MongoDBObject) = Some(TaskDetail(
      name = dbo.as[String]("name"),
      description = dbo.as[String]("description"),
      verbose = dbo.as[String]("verbose")
    ))
  }

  val throwableDetail = new MongoFormat[ThrowableDetail] {
    def toDBO(a: ThrowableDetail) = {
      val fields:List[(String,Any)] =
        List(
          "name" -> a.name,
          "message" -> a.message,
          "stackTrace" -> a.stackTrace
        ) ++ a.cause.map("cause" -> toDBO(_))
      fields.toMap
    }

    def fromDBO(dbo: MongoDBObject): Option[ThrowableDetail] = Some(ThrowableDetail(
      name = dbo.as[String]("name"),
      message = dbo.as[String]("message"),
      stackTrace = dbo.as[String]("stackTrace"),
      cause = dbo.getAs[DBObject]("cause").flatMap(dbo => fromDBO(dbo))
    ))
  }
}

case class DeploymentKeyDocument(name: String, action: String, stack: String, region: String)

object DeploymentKeyDocument extends MongoSerialisable[DeploymentKeyDocument] {
  implicit def formats: Format[DeploymentKeyDocument] = Json.format[DeploymentKeyDocument]

  implicit val deploymentKeyFormat: MongoFormat[DeploymentKeyDocument] = new DeploymentKeyFormat
  private class DeploymentKeyFormat extends MongoFormat[DeploymentKeyDocument] {
    def toDBO(a: DeploymentKeyDocument) = {
      val fields: List[(String, Any)] =
        List(
          "name" -> a.name,
          "action" -> a.action,
          "stack" -> a.stack,
          "region" -> a.region
        )
      fields.toMap
    }

    def fromDBO(dbo: MongoDBObject) = {
      Some(DeploymentKeyDocument(
        name = dbo.as[String]("name"),
        action = dbo.as[String]("action"),
        stack = dbo.as[String]("stack"),
        region = dbo.as[String]("region")
      ))
    }
  }
}



case object AllDocument extends DeploymentSelectorDocument {
  def dboFields = Nil
}

case class DeploymentKeysSelectorDocument(ids: List[DeploymentKeyDocument]) extends DeploymentSelectorDocument {
  def dboFields = List("keys" -> ids.map(_.toDBO))
}
object DeploymentKeysSelectorDocument {
  implicit def formats: Format[DeploymentKeysSelectorDocument] = Json.format[DeploymentKeysSelectorDocument]
}
