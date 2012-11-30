package persistence

import java.util.UUID
import org.joda.time.DateTime
import akka.agent.Agent
import com.mongodb.casbah.commons.conversions.scala.RegisterJodaTimeConversionHelpers
import com.novus.salat._
import com.novus.salat.StringTypeHintStrategy
import controllers.Logging
import deployment.{DeployV2Record, Record, DeployRecord}
import magenta._
import akka.actor.ActorSystem
import controllers.SimpleDeployDetail

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

trait RecordConverter {
  def uuid:UUID
  def startTime:DateTime
  def params: ParametersDocument
  def status:RunState.Value
  lazy val deployDocument = DeployRecordDocument(uuid, startTime, params, status)
  def logDocuments:Seq[LogDocument]
}

case class RecordV1Converter(uuid:UUID, startTime:DateTime, params: ParametersDocument, status:RunState.Value, messageStacks:List[MessageStack] = Nil) extends RecordConverter with Logging {
  def +(newStack: MessageStack): RecordConverter = copy(messageStacks = messageStacks ::: List(newStack))
  def +(newStatus: RunState.Value): RecordConverter = copy(status = newStatus)

  def apply(stack: MessageStack): Option[LogDocument] = None

  def apply: (DeployRecordDocument, Seq[LogDocument]) = (deployDocument, logDocuments)

  lazy val logDocuments = Nil
}

case class RecordV2Converter(uuid:UUID, startTime:DateTime, params: ParametersDocument, status:RunState.Value, messages:List[MessageWrapper] = Nil) extends RecordConverter with Logging {
  def +(newWrapper: MessageWrapper): RecordV2Converter = copy(messages = messages ::: List(newWrapper))
  def +(newStatus: RunState.Value): RecordV2Converter = copy(status = newStatus)

  def apply(message: MessageWrapper): Option[LogDocument] = {
    val stackId=message.messageId
    logDocuments.find(_.id == stackId)
  }

  def apply: (DeployRecordDocument, Seq[LogDocument]) = (deployDocument, logDocuments)

  def buildLogDocuments(messages: List[MessageWrapper], parent:Option[LogDocument]): Seq[LogDocument] = {
    messages.map { message =>
      LogDocument(uuid, message.messageId, message.context.parentId, message.stack.top, message.stack.time)
    }
  }

  lazy val logDocuments = {
    val logDocumentSeq: Seq[LogDocument] = buildLogDocuments(messages, None)
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
    RecordV1Converter(record.uuid, record.time, params, record.state, record.messageStacks)
  }

  def apply(record: DeployV2Record): RecordV2Converter = {
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
    RecordV2Converter(record.uuid, record.time, params, record.state, record.messages)
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
    DeployV2Record(
      deploy.startTime,
      deploy.deployTypeEnum,
      deploy.uuid,
      parameters,
      messageWrappers,
      Some(deploy.status)
    )

  lazy val messageWrappers: List[MessageWrapper] = {
    if (logs.isEmpty) Nil else convertToMessageWrappers(LogDocumentTree(logs))
  }

  def convertToMessageWrappers(tree: LogDocumentTree): List[MessageWrapper] = convertToMessageWrappers(tree, tree.roots.head)

  def convertToMessageWrappers(tree: LogDocumentTree, log: LogDocument, messagesTail: List[Message] = Nil): List[MessageWrapper] = {
    val children = tree.childrenOf(log).toList
    log.document match {
      case leaf if children.isEmpty =>
        List(messageWrapper(log, MessageStack(leaf.asMessage(parameters, messagesTail.headOption) :: messagesTail, log.time)))
      case node => {
        val message:Message = node.asMessage(parameters)
        messageWrapper(log,MessageStack(StartContext(message) :: messagesTail, log.time)) ::
          children.flatMap(child => convertToMessageWrappers(tree, child, message :: messagesTail))
      }
    }
  }

  def messageWrapper(log: LogDocument, stack: MessageStack): MessageWrapper = {
    MessageWrapper(MessageContext(log.deploy, parameters, log.parent), log.id, stack)
  }
}

trait DocumentStore {
  def writeDeploy(deploy: DeployRecordDocument) {}
  def writeLog(log: LogDocument) {}
  def updateStatus(uuid: UUID, status: RunState.Value) {}
  def readDeploy(uuid: UUID): Option[DeployRecordDocument] = None
  def readLogs(uuid: UUID): Iterable[LogDocument] = Nil
  def getDeployV2UUIDs(limit: Int = 0): Iterable[SimpleDeployDetail] = Nil
  def deleteDeployLogV2(uuid: UUID) {}
}

case class DocumentStoreConverter(documentStore: DocumentStore) {
  implicit val actorSystem = ActorSystem("document-store")

  val deployConverterMap =
    Agent(Map.empty[UUID,Agent[RecordV2Converter]].withDefault{key =>
      throw new IllegalArgumentException("Don't know deploy ID %s" format key.toString)})

  def newDeploy(record: DeployV2Record) {
    if (!record.messages.isEmpty) throw new IllegalArgumentException
    val converter = RecordConverter(record)
    documentStore.writeDeploy(converter.deployDocument)
    deployConverterMap.send { _ + (record.uuid -> Agent(converter)) }
  }

  def newMessage(deployId: UUID, message: MessageWrapper) {
    deployConverterMap()(deployId).send { converter =>
      val newConverter = converter + message
      newConverter(message).foreach(documentStore.writeLog)
      newConverter
    }
  }

  def updateDeployStatus(record: DeployV2Record) {
    deployConverterMap()(record.uuid).send { converter =>
      documentStore.updateStatus(record.uuid, record.state)
      converter + record.state
    }
  }

  def getDeploy(uuid:UUID): Option[DeployV2Record] = {
    val deployDocument = documentStore.readDeploy(uuid)
    val logDocuments = documentStore.readLogs(uuid)
    deployDocument.map { deploy =>
      DocumentConverter(deploy, logDocuments.toSeq).deployRecord
    }
  }

  def getDeployList(limit: Int): Seq[DeployV2Record] = {
    documentStore.getDeployV2UUIDs(limit).flatMap(info => getDeploy(info.uuid)).toSeq
  }

  def close(deployId:UUID) {
    deployConverterMap.send( _ - deployId )
  }
}