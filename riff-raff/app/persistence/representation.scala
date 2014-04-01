package persistence

import java.util.UUID
import org.joda.time.DateTime
import magenta._
import deployment.TaskType
import com.mongodb.DBObject
import com.mongodb.casbah.commons.Implicits._
import com.mongodb.casbah.commons.{MongoDBList, MongoDBObject}

case class DeployRecordDocument(uuid:UUID,
                                stringUUID:Option[String],
                                startTime: DateTime,
                                parameters: ParametersDocument,
                                status: RunState.Value,
                                summarised: Option[Boolean] = None,
                                totalTasks: Option[Int] = None,
                                completedTasks: Option[Int] = None,
                                lastActivityTime: Option[DateTime] = None) {
  lazy val deployTypeEnum = TaskType.withName(parameters.deployType)
}
object DeployRecordDocument extends MongoSerialisable[DeployRecordDocument] {
  def apply(uuid:String, startTime: DateTime, parameters: ParametersDocument, status: String): DeployRecordDocument = {
    DeployRecordDocument(UUID.fromString(uuid), Some(uuid), startTime, parameters, RunState.withName(status))
  }
  implicit val deployFormat:MongoFormat[DeployRecordDocument] = new DeployMongoFormat
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
             a.lastActivityTime.map("lastActivityTime" ->)
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
        lastActivityTime = dbo.getAs[DateTime]("lastActivityTime")
      )
    )
  }
}

case class ParametersDocument(
  deployer: String,
  deployType: String,
  projectName: String,
  buildId: String,
  stage: String,
  recipe: String,
  stacks: List[String],
  hostList: List[String],
  tags: Map[String,String]
)

object ParametersDocument extends MongoSerialisable[ParametersDocument] {
  implicit val parametersFormat:MongoFormat[ParametersDocument] = new ParameterMongoFormat
  private class ParameterMongoFormat extends MongoFormat[ParametersDocument] {
    def toDBO(a: ParametersDocument) = {
      val fields:List[(String,Any)] =
        List(
          "deployer" -> a.deployer,
          "deployType" -> a.deployType,
          "projectName" -> a.projectName,
          "buildId" -> a.buildId,
          "stage" -> a.stage,
          "recipe" -> a.recipe,
          "stacks" -> a.stacks,
          "hostList" -> a.hostList,
          "tags" -> a.tags
        )
      fields.toMap
    }
    def fromDBO(dbo: MongoDBObject) = Some(ParametersDocument(
      deployer = dbo.as[String]("deployer"),
      deployType = dbo.as[String]("deployType"),
      projectName = dbo.as[String]("projectName"),
      buildId = dbo.as[String]("buildId"),
      stage = dbo.as[String]("stage"),
      recipe = dbo.as[String]("recipe"),
      stacks = dbo.getAsOrElse[MongoDBList]("stacks", MongoDBList()).map(_.asInstanceOf[String]).toList,
      hostList = dbo.as[MongoDBList]("hostList").map(_.asInstanceOf[String]).toList,
      tags = dbo.as[DBObject]("tags").map(entry => (entry._1, entry._2.asInstanceOf[String])).toMap
    ))
  }
}

case class LogDocument(
  deploy: UUID,
  id: UUID,
  parent: Option[UUID],
  document: MessageDocument,
  time: DateTime
)

object LogDocument extends MongoSerialisable[LogDocument] {
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

  implicit val logFormat:MongoFormat[LogDocument] = new LogMongoFormat
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
  val host = new MongoFormat[Host] {
    def toDBO(a: Host) = {
      val fields:List[(String,Any)] =
        List(
          "name" -> a.name,
          "apps" -> a.apps.map(a => MongoDBObject("name" -> a.name)).toList,
          "stage" -> a.stage
        ) ++ a.connectAs.map("connectAs" ->)
      fields.toMap
    }

    def fromDBO(dbo: MongoDBObject) = Some(Host(
      name = dbo.as[String]("name"),
      apps = dbo.as[List[DBObject]]("apps").map{dbo =>
        (dbo.getAs[String]("name"),dbo.getAs[String]("stack"),dbo.getAs[String]("app")) match {
          case (Some(name), None, None) => App(name)
          case (None, Some(stack), Some(app)) => App(app)
          case other => throw new IllegalArgumentException(s"Don't know how to construct App from tuple $other")
        }
      }.toSet,
      stage = dbo.as[String]("stage"),
      connectAs = dbo.getAs[String]("connectAs")
    ))
  }

  val taskDetail = new MongoFormat[TaskDetail] {
    def toDBO(a: TaskDetail) = {
      val fields:List[(String,Any)] =
        List(
          "name" -> a.name,
          "description" -> a.description,
          "verbose" -> a.verbose,
          "taskHosts" -> a.taskHosts.map(DetailConversions.host.toDBO)
        )
      fields.toMap
    }

    def fromDBO(dbo: MongoDBObject) = Some(TaskDetail(
      name = dbo.as[String]("name"),
      description = dbo.as[String]("description"),
      verbose = dbo.as[String]("verbose"),
      taskHosts = dbo.as[MongoDBList]("taskHosts").flatMap(item => host.fromDBO(item.asInstanceOf[DBObject])).toList
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
  override lazy val dboFields = List("taskList" -> taskList.map(DetailConversions.taskDetail.toDBO))
}
case class TaskRunDocument(task: TaskDetail) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = TaskRun(task)
  override lazy val dboFields = List("task" -> DetailConversions.taskDetail.toDBO(task))
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
  override lazy val dboFields = List("text" -> text, "detail" -> DetailConversions.throwableDetail.toDBO(detail))
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
        throw new IllegalArgumentException(s"Don't know how to serialise Message of type ${from.getClass.getName}")
    }
  }
  def from(dbo:DBObject): MessageDocument = {
    import DetailConversions._
    dbo.as[String]("_typeHint") match {
      case "persistence.DeployDocument" => DeployDocument()
      case "persistence.TaskListDocument" =>
        TaskListDocument(dbo.as[MongoDBList]("taskList").flatMap(dbo => taskDetail.fromDBO(dbo.asInstanceOf[DBObject])).toList)
      case "persistence.TaskRunDocument" => TaskRunDocument(taskDetail.fromDBO(dbo.as[DBObject]("task")).get)
      case "persistence.InfoDocument" => InfoDocument(dbo.as[String]("text"))
      case "persistence.CommandOutputDocument" => CommandOutputDocument(dbo.as[String]("text"))
      case "persistence.CommandErrorDocument" => CommandErrorDocument(dbo.as[String]("text"))
      case "persistence.VerboseDocument" => VerboseDocument(dbo.as[String]("text"))
      case "persistence.FailDocument" =>
        FailDocument(dbo.as[String]("text"), throwableDetail.fromDBO(dbo.as[DBObject]("detail")).get)
      case "persistence.FinishContextDocument" => FinishContextDocument()
      case "persistence.FailContextDocument" => FailContextDocument()
      case hint =>
        throw new IllegalArgumentException(s"Don't know how to construct MessageDocument of type $hint}")
    }
  }
}