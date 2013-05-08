package persistence

import java.util.UUID
import org.joda.time.DateTime
import magenta._
import deployment.{Record, TaskType}
import com.mongodb.DBObject
import com.mongodb.casbah.commons.Implicits._
import com.mongodb.casbah.commons.MongoDBObject

case class DeployRecordDocument(uuid:UUID, stringUUID:Option[String], startTime: DateTime, parameters: ParametersDocument, status: RunState.Value, summarised: Option[Boolean] = None) {
  lazy val deployTypeEnum = TaskType.withName(parameters.deployType)
  lazy val asDBObject:DBObject = {
    val fields:List[(String,Any)] =
      List(
        "uuid" -> uuid,
        "startTime" -> startTime,
        "parameters" -> parameters.asDBObject,
        "status" -> status.toString
      ) ++ stringUUID.map("stringUUID" -> _) ++ summarised.map("summarised" -> _)
    fields.toMap
  }
}
object DeployRecordDocument {
  def apply(uuid:String, startTime: DateTime, parameters: ParametersDocument, status: String): DeployRecordDocument = {
    DeployRecordDocument(UUID.fromString(uuid), Some(uuid), startTime, parameters, RunState.withName(status))
  }
  def from(dbo: MongoDBObject) = DeployRecordDocument(
    uuid = dbo.as[UUID]("uuid"),
    stringUUID = dbo.getAs[String]("stringUUID"),
    startTime = dbo.as[DateTime]("startTime"),
    parameters = ParametersDocument.from(dbo.as[DBObject]("parameters")),
    status = RunState.withName(dbo.as[String]("status")),
    summarised = dbo.getAs[Boolean]("summarised")
  )
}

case class ParametersDocument(
  deployer: String,
  deployType: String,
  projectName: String,
  buildId: String,
  stage: String,
  recipe: String,
  hostList: List[String],
  tags: Map[String,String]
) {
  lazy val asDBObject:DBObject = {
    val fields:List[(String,Any)] =
      List(
        "deployer" -> deployer,
        "deployType" -> deployType,
        "projectName" -> projectName,
        "buildId" -> buildId,
        "stage" -> stage,
        "recipe" -> recipe,
        "hostList" -> hostList,
        "tags" -> tags
      )
    fields.toMap
  }
}

object ParametersDocument {
  def from(dbo: MongoDBObject) = ParametersDocument(
    deployer = dbo.as[String]("deployer"),
    deployType = dbo.as[String]("deployType"),
    projectName = dbo.as[String]("projectName"),
    buildId = dbo.as[String]("buildId"),
    stage = dbo.as[String]("stage"),
    recipe = dbo.as[String]("recipe"),
    hostList = dbo.as[List[String]]("hostList"),
    tags = dbo.as[Map[String,String]]("tags")
  )
}

case class LogDocument(
  deploy: UUID,
  id: UUID,
  parent: Option[UUID],
  document: MessageDocument,
  time: DateTime
) {
  lazy val asDBObject:DBObject = {
    val fields:List[(String,Any)] =
      List(
        "deploy" -> deploy,
        "id" -> id,
        "document" -> document.asDBObject,
        "time" -> time
      ) ++ parent.map("parent" -> _)
    fields.toMap
  }
}
object LogDocument {
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
  def from(dbo: MongoDBObject) = LogDocument(
    deploy = dbo.as[UUID]("deploy"),
    id = dbo.as[UUID]("id"),
    parent = dbo.getAs[UUID]("parent"),
    document = MessageDocument.from(dbo.as[DBObject]("document")),
    time = dbo.as[DateTime]("time")
  )
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
  def taskDetail2dbo(task: TaskDetail):DBObject = {
    val fields:List[(String,Any)] =
      List(
        "name" -> task.name,
        "description" -> task.description,
        "verbose" -> task.verbose,
        "taskHosts" -> task.taskHosts.map(DetailConversions.host2dbo)
      )
    fields.toMap
  }
  def dbo2taskDetail(dbo: DBObject):TaskDetail = {
    TaskDetail(
      name = dbo.as[String]("name"),
      description = dbo.as[String]("description"),
      verbose = dbo.as[String]("verbose"),
      taskHosts = dbo.as[List[DBObject]]("taskHosts").map(dbo2host)
    )
  }
  def host2dbo(host:Host):DBObject = {
    val fields:List[(String,Any)] =
      List(
        "name" -> host.name,
        "apps" -> host.apps.map(app => MongoDBObject("name" -> app.name)).toList,
        "stage" -> host.stage
      ) ++ host.connectAs.map("connectAs" ->)
    fields.toMap
  }
  def dbo2host(dbo: DBObject):Host = {
    Host(
      name = dbo.as[String]("name"),
      apps = dbo.as[List[DBObject]]("apps").map(dbo => App(dbo.as[String]("name"))).toSet,
      stage = dbo.as[String]("stage"),
      connectAs = dbo.getAs[String]("connectAs")
    )
  }
  def throwableDetail2dbo(throwable: ThrowableDetail):DBObject = {
    val fields:List[(String,Any)] =
      List(
        "name" -> throwable.name,
        "message" -> throwable.message,
        "stackTrace" -> throwable.stackTrace
      ) ++ throwable.cause.map("cause" -> throwableDetail2dbo(_))
    fields.toMap
  }
  def dbo2throwableDetail(dbo: DBObject):ThrowableDetail = {
    ThrowableDetail(
      name = dbo.as[String]("name"),
      message = dbo.as[String]("message"),
      stackTrace = dbo.as[String]("stackTrace"),
      cause = dbo.getAs[DBObject]("cause").map(dbo2throwableDetail)
    )
  }
}

trait MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message] = None): Message
  def asDBObject:DBObject = {
    val fields:List[(String,Any)] =
      List("_typeHint" -> getClass.getName) ++ dboFields
    fields.toMap
  }
  def dboFields:List[(String,Any)] = Nil
}

case class DeployDocument() extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = Deploy(params)
}
case class TaskListDocument(taskList: List[TaskDetail]) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = TaskList(taskList)
  override lazy val dboFields = List("taskList" -> taskList.map(DetailConversions.taskDetail2dbo))
}
case class TaskRunDocument(task: TaskDetail) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = TaskRun(task)
  override lazy val dboFields = List("task" -> DetailConversions.taskDetail2dbo(task))
}
case class InfoDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = Info(text)
  override lazy val dboFields = List("text" -> text)
}
case class CommandOutputDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = CommandOutput(text)
  override lazy val dboFields = List("text" -> text)
}
case class CommandErrorDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = CommandError(text)
  override lazy val dboFields = List("text" -> text)
}
case class VerboseDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = Verbose(text)
  override lazy val dboFields = List("text" -> text)
}
case class FailDocument(text: String, detail: ThrowableDetail) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = Fail(text, detail)
  override lazy val dboFields = List("text" -> text, "detail" -> DetailConversions.throwableDetail2dbo(detail))
}
case class FinishContextDocument() extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = FinishContext(originalMessage.get)
}
case class FailContextDocument() extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = FailContext(originalMessage.get)
}

object MessageDocument {
  def apply(from: Message): MessageDocument = {
    from match {
      case Deploy(_) => DeployDocument()
      case TaskList(taskList) => TaskListDocument(taskList)
      case TaskRun(task) => TaskRunDocument(task)
      case Info(text) => InfoDocument(text)
      case CommandOutput(text) => CommandOutputDocument(text)
      case CommandError(text) => CommandErrorDocument(text)
      case Verbose(text) => VerboseDocument(text)
      case Fail(text, detail) => FailDocument(text,detail)
      case FinishContext(message) => FinishContextDocument()
      case FailContext(message) => FailContextDocument()
      case _ =>
        throw new IllegalArgumentException("Don't know how to serialise Message of type %s" format from.getClass.getName)
    }
  }
  def from(dbo:DBObject): MessageDocument = {
    import DetailConversions._
    dbo.as[String]("_typeHint") match {
      case "persistence.DeployDocument" => DeployDocument()
      case "persistence.TaskListDocument" => TaskListDocument(dbo.as[List[DBObject]]("taskList").map(dbo2taskDetail))
      case "persistence.TaskRunDocument" => TaskRunDocument(dbo2taskDetail(dbo.as[DBObject]("task")))
      case "persistence.InfoDocument" => InfoDocument(dbo.as[String]("text"))
      case "persistence.CommandOutputDocument" => CommandOutputDocument(dbo.as[String]("text"))
      case "persistence.CommandErrorDocument" => CommandErrorDocument(dbo.as[String]("text"))
      case "persistence.VerboseDocument" => VerboseDocument(dbo.as[String]("text"))
      case "persistence.FailDocument" => FailDocument(dbo.as[String]("text"), dbo2throwableDetail(dbo.as[DBObject]("detail")))
      case "persistence.FinishContextDocument" => FinishContextDocument()
      case "persistence.FailContextDocument" => FailContextDocument()
      case hint => throw new IllegalArgumentException("Don't know how to construct MessageDocument of type %s" format hint)
    }
  }
}