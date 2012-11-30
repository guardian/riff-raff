package persistence

import java.util.UUID
import org.joda.time.DateTime
import com.novus.salat.annotations.raw.Salat
import magenta._
import deployment.{Record, Task, DeployRecord}

case class DeployRecordDocument(uuid:UUID, startTime: DateTime, parameters: ParametersDocument, status: RunState.Value) {
  lazy val deployTypeEnum = Task.withName(parameters.deployType)
}
object DeployRecordDocument {
  def apply(record: Record): DeployRecordDocument = {
    val sourceParams = record.parameters
    val params = ParametersDocument(
      deployer = sourceParams.deployer.name,
      projectName = sourceParams.build.projectName,
      buildId = sourceParams.build.id,
      stage = sourceParams.stage.name,
      recipe = sourceParams.recipe.name,
      hostList = sourceParams.hostList,
      buildDescription = None,
      deployType = record.taskType.toString
    )
    DeployRecordDocument(record.uuid, record.time, params, record.state)
  }
}

case class ParametersDocument(
  deployer: String,
  deployType: String,
  projectName: String,
  buildId: String,
  buildDescription: Option[String],
  stage: String,
  recipe: String,
  hostList: List[String]
)

case class LogDocument(
  deploy: UUID,
  id: UUID,
  parent: Option[UUID],
  document: MessageDocument,
  time: DateTime
)
object LogDocument {
  def apply(deploy: UUID, id: UUID, parent: Option[UUID], document: Message, time: DateTime): LogDocument = {
    val messageDocument = document match {
        case StartContext(message) => message
        case other => other
      }
    LogDocument(deploy, id, parent, messageDocument.asMessageDocument, time)
  }
}

case class LogDocumentTree(documents: Seq[LogDocument]) {
  lazy val idMap = documents.map(log => log.id-> log).toMap
  lazy val parentMap = documents.filter(_.parent.isDefined).groupBy(_.parent.get).withDefaultValue(Nil)

  lazy val roots = documents.filter(_.parent.isEmpty)

  def parentOf(child: LogDocument): Option[LogDocument] = child.parent.flatMap(idMap.get)
  def childrenOf(parent: LogDocument): Seq[LogDocument] = parentMap(parent.id)

  def traverseTree(root: LogDocument): Seq[LogDocument] = {
    root :: childrenOf(root).flatMap(traverseTree).toList
  }
}

@Salat
trait MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message] = None): Message
}

case class DeployDocument() extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = Deploy(params)
}
case class TaskListDocument(taskList: List[TaskDetail]) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = TaskList(taskList)
}
case class TaskRunDocument(task: TaskDetail) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = TaskRun(task)
}
case class InfoDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = Info(text)
}
case class CommandOutputDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = CommandOutput(text)
}
case class CommandErrorDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = CommandError(text)
}
case class VerboseDocument(text: String) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = Verbose(text)
}
case class FailDocument(text: String, detail: ThrowableDetail) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = Fail(text, detail)
}
case class FinishContextDocument() extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = FinishContext(originalMessage.get)
}
case class FailContextDocument(detail: ThrowableDetail) extends MessageDocument {
  def asMessage(params: DeployParameters, originalMessage: Option[Message]) = FailContext(originalMessage.get, detail)
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
      case FailContext(message,detail) => FailContextDocument(detail)
      case _ =>
        throw new IllegalArgumentException("Don't know how to serialise Message of type %s" format from.getClass.getName)
    }
  }
}