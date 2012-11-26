package persistence

import java.util.UUID
import org.joda.time.DateTime
import akka.agent.Agent
import com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers
import com.novus.salat._
import com.novus.salat.StringTypeHintStrategy
import controllers.Logging
import deployment.DeployRecord
import magenta._
import akka.actor.ActorSystem

trait DocumentGraters {
  RegisterJodaTimeConversionHelpers()
  def loader:Option[ClassLoader]
  val documentContext = {
    val context = new Context {
      val name = "global"
      override val typeHintStrategy = StringTypeHintStrategy(TypeHintFrequency.WhenNecessary)
    }
    loader.foreach(context.registerClassLoader(_))
    context.registerPerClassKeyOverride(classOf[DeployRecordDocument], remapThis = "uuid", toThisInstead = "_id")
    context
  }
  val deployGrater = {
    implicit val context = documentContext
    grater[DeployRecordDocument]
  }
  val logDocumentGrater = {
    implicit val context = documentContext
    grater[LogDocument]
  }
}

case class RecordConverter(uuid:UUID, startTime:DateTime, params: ParametersDocument, status:RunState.Value, messageStacks:List[MessageStack] = Nil) extends Logging {
  def +(newStack: MessageStack): RecordConverter = copy(messageStacks = messageStacks ::: List(newStack))
  def +(newStatus: RunState.Value): RecordConverter = copy(status = newStatus)

  def apply(stack: MessageStack): Option[LogDocument] = {
    val stackId=stack.id
    logDocuments.find(_.id == stackId)
  }

  def apply: (DeployRecordDocument, Seq[LogDocument]) = (deployDocument, logDocuments)

  def buildLogDocuments(messageStack: MessageStack, parent:Option[LogDocument]): Seq[LogDocument] = {
    messageStack.top match {
      case StartContext(message) => {
        val thisNode = LogDocument(uuid, messageStack.id, parent.map(_.id), message.asMessageDocument, messageStack.time)

        // find children
        val expectedTail = message :: messageStack.messages.tail
        val childStacks = messageStacks.filter(_.messages.tail == expectedTail).filterNot(_.top.isEndContext)
        val children = childStacks.flatMap(childStack => buildLogDocuments(childStack, Some(thisNode)))

        // find end node: parent nodes match, is a FinishContext or FailContext for 'message'
        val endNodes = messageStacks.filter { stack =>
          stack.top match {
            case FinishContext(finishMessage) if message==finishMessage => true
            case FailContext(failMessage, _) if message==failMessage => true
            case _ => false
          }
        }
        if (endNodes.length > 1) throw new IllegalStateException("Found more than one matching end statement for a message")
        val endDocument = endNodes.headOption.map(stack => LogDocument(uuid, stack.id, Some(thisNode.id), stack.top.asMessageDocument, stack.time))

        thisNode :: children.toList ::: endDocument.toList
      }
      case FinishContext(_) => throw new IllegalArgumentException("Message type FinishContext not valid to create a LogTreeDocument")
      case FailContext(_,_) => throw new IllegalArgumentException("Message type FailContext not valid to create a LogTreeDocument")
      case simpleMessage => List(LogDocument(uuid, messageStack.id, parent.map(_.id), simpleMessage.asMessageDocument, messageStack.time))
    }
  }

  lazy val deployDocument = DeployRecordDocument(uuid, startTime, params, status)

  lazy val logDocuments = {
    val logDocumentSeq: Seq[LogDocument] = if (messageStacks.isEmpty) Nil else buildLogDocuments(messageStacks.head, None)
    val ids = logDocumentSeq.map(_.id)
    if (ids.size != ids.toSet.size) log.error("Key collision detected in log of deploy %s" format uuid)
    logDocumentSeq
  }
}

object RecordConverter {
  def apply(record: DeployRecord): RecordConverter = {
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
    RecordConverter(record.uuid, record.time, params, record.state, record.messageStacks)
  }
}

case class DocumentConverter(deploy: DeployRecordDocument, logs: Seq[LogDocument]) {

  lazy val parameters = DeployParameters(
    Deployer(deploy.parameters.deployer),
    Build(deploy.parameters.projectName, deploy.parameters.buildId),
    Stage(deploy.parameters.stage),
    RecipeName(deploy.parameters.recipe),
    deploy.parameters.hostList
  )

  lazy val deployRecord =
    DeployRecord(
      deploy.startTime,
      deploy.deployTypeEnum,
      deploy.uuid,
      parameters,
      messageStacks
    )

  lazy val messageStacks: List[MessageStack] = {
    convertToMessageStacks(LogDocumentTree(logs))
  }

  def convertToMessageStacks(tree: LogDocumentTree): List[MessageStack] = convertToMessageStacks(tree, tree.roots.head)

  def convertToMessageStacks(tree: LogDocumentTree, log: LogDocument, messagesTail: List[Message] = Nil): List[MessageStack] = {
    val children = tree.childrenOf(log).toList
    log.document match {
      case FinishContextDocument() =>
        List(MessageStack(FinishContext(messagesTail.head) :: messagesTail.tail, log.time))
      case FailContextDocument(detail) =>
        List(MessageStack(FailContext(messagesTail.head, detail) :: messagesTail.tail, log.time))
      case leaf if children.isEmpty =>
        List(MessageStack(leaf.asMessage(parameters, messagesTail.headOption) :: messagesTail, log.time))
      case node => {
        val message:Message = node.asMessage(parameters)
        MessageStack(StartContext(message) :: messagesTail, log.time) ::
          children.flatMap(child => convertToMessageStacks(tree, child, message :: messagesTail))
      }
    }
  }
}

trait DocumentStore {
  def writeDeploy(deploy: DeployRecordDocument) {}
  def writeLog(log: LogDocument) {}
  def updateStatus(uuid: UUID, status: RunState.Value) {}
  def readDeploy(uuid: UUID): Option[DeployRecordDocument] = None
  def readLogs(uuid: UUID): Iterable[LogDocument] = Nil
}

case class DocumentStoreConverter(documentStore: DocumentStore) {
  implicit val actorSystem = ActorSystem("document-store")

  val deployConverterMap =
    Agent(Map.empty[UUID,Agent[RecordConverter]].withDefault{key =>
      throw new IllegalArgumentException("Don't know deploy ID %s" format key.toString)})

  def newDeploy(record: DeployRecord)(block: DeployRecordDocument => Unit) {
    if (!record.messageStacks.isEmpty) throw new IllegalArgumentException
    val converter = RecordConverter(record)
    documentStore.writeDeploy(converter.deployDocument)
    deployConverterMap.send { _ + (record.uuid -> Agent(converter)) }
  }

  def newStack(deployId: UUID, stack: MessageStack) {
    deployConverterMap()(deployId).send { converter =>
      val newConverter = converter + stack
      newConverter(stack).foreach(documentStore.writeLog)
      newConverter
    }
  }

  def updateDeployStatus(record: DeployRecord) {
    deployConverterMap()(record.uuid).send { converter =>
      documentStore.updateStatus(record.uuid, record.state)
      converter + record.state
    }
  }

  def getDeploy(uuid:UUID): Option[DeployRecord] = {
    val deployDocument = documentStore.readDeploy(uuid)
    val logDocuments = documentStore.readLogs(uuid)
    deployDocument.map { deploy =>
      DocumentConverter(deploy, logDocuments.toSeq).deployRecord
    }
  }

  def close(deployId:UUID) {
    deployConverterMap.send( _ - deployId )
  }
}