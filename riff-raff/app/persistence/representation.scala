package persistence

import java.util.UUID
import org.joda.time.DateTime
import com.novus.salat.annotations.raw.Salat
import magenta._
import deployment.DeployRecord

case class DeployRecordDocument(uuid:UUID, startTime: DateTime, parameters: ParametersDocument, status: RunState.Value)
object DeployRecordDocument {
  def apply(record: DeployRecord): DeployRecordDocument = {
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
  id: String,
  parent: Option[String],
  document: MessageDocument,
  time: DateTime
)
object LogDocument {
  def apply(deploy: UUID, id: String, parent: Option[String], document: Message, time: DateTime): LogDocument = {
    LogDocument(deploy, id, parent, document.asMessageDocument, time)
  }
}

case class LogDocumentTree(documents: Seq[LogDocument]) {
  lazy val idMap = documents.map(log => log.id-> log).toMap
  lazy val parentMap = documents.filter(_.parent.isDefined).groupBy(_.parent.get).withDefaultValue(Nil)

  lazy val roots = documents.filter(_.parent.isEmpty)

  def parentOf(child: LogDocument): Option[LogDocument] = child.parent.flatMap(idMap.get)
  def childrenOf(parent: LogDocument): Seq[LogDocument] = parentMap(parent.id)
}

@Salat
trait MessageDocument

case class DeployDocument() extends MessageDocument {
  def getMessage(context: DeployRecordDocument) = {
    val params = DeployParameters(
      Deployer(context.parameters.deployer),
      Build(context.parameters.projectName, context.parameters.buildId),
      Stage(context.parameters.stage),
      RecipeName(context.parameters.recipe),
      context.parameters.hostList
    )
    Deploy(params)
  }
}

case class TaskListDocument(taskList: List[TaskDetail]) extends MessageDocument
case class TaskRunDocument(task: TaskDetail) extends MessageDocument
case class InfoDocument(text: String) extends MessageDocument
case class CommandOutputDocument(text: String) extends MessageDocument
case class CommandErrorDocument(text: String) extends MessageDocument
case class VerboseDocument(text: String) extends MessageDocument
case class FailDocument(text: String, detail: ThrowableDetail) extends MessageDocument
case class FinishContextDocument() extends MessageDocument
case class FailContextDocument(detail: ThrowableDetail) extends MessageDocument

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