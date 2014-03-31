package persistence

import java.util.UUID
import org.joda.time.DateTime
import controllers.Logging
import deployment.{DeployFilter, DeployRecord, PaginationView}
import magenta._
import controllers.SimpleDeployDetail

case class RecordConverter(uuid:UUID, startTime:DateTime, params: ParametersDocument, status:RunState.Value, messages:List[MessageWrapper] = Nil) extends Logging {
  def +(newWrapper: MessageWrapper): RecordConverter = copy(messages = messages ::: List(newWrapper))
  def +(newStatus: RunState.Value): RecordConverter = copy(status = newStatus)

  def apply(message: MessageWrapper): Option[LogDocument] = {
    val stackId=message.messageId
    logDocuments.find(_.id == stackId)
  }

  def apply: (DeployRecordDocument, Seq[LogDocument]) = (deployDocument, logDocuments)

  lazy val deployDocument = DeployRecordDocument(uuid, Some(uuid.toString), startTime, params, status)

  lazy val logDocuments = {
    val logDocumentSeq: Seq[LogDocument] = messages.map(LogDocument(_))
    val ids = logDocumentSeq.map(_.id)
    if (ids.size != ids.toSet.size) log.error(s"Key collision detected in log of deploy $uuid")
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
      stacks = sourceParams.stacks.map(_.name).toList,
      hostList = sourceParams.hostList,
      deployType = record.taskType.toString,
      tags = record.metaData
    )
    RecordConverter(record.uuid, record.time, params, record.state, record.messages)
  }
}

case class DocumentConverter(deploy: DeployRecordDocument, logs: Seq[LogDocument]) {

  lazy val parameters = DeployParameters(
    Deployer(deploy.parameters.deployer),
    Build(deploy.parameters.projectName, deploy.parameters.buildId),
    Stage(deploy.parameters.stage),
    RecipeName(deploy.parameters.recipe),
    deploy.parameters.stacks.map(NamedStack(_)),
    deploy.parameters.hostList
  )

  lazy val deployRecord =
    DeployRecord(
      deploy.startTime,
      deploy.deployTypeEnum,
      deploy.uuid,
      parameters,
      deploy.parameters.tags,
      messageWrappers,
      Some(deploy.status),
      deploy.totalTasks,
      deploy.completedTasks,
      deploy.lastActivityTime
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
  def updateDeploySummary(uuid: UUID, totalTasks:Option[Int], completedTasks:Int, lastActivityTime:DateTime) {}
  def readDeploy(uuid: UUID): Option[DeployRecordDocument] = None
  def readLogs(uuid: UUID): Iterable[LogDocument] = Nil
  def getDeployUUIDs(limit: Int = 0): Iterable[SimpleDeployDetail] = Nil
  def getDeploys(filter: Option[DeployFilter], pagination: PaginationView): Iterable[DeployRecordDocument] = Nil
  def countDeploys(filter: Option[DeployFilter]): Int = 0
  def deleteDeployLog(uuid: UUID) {}
  def getLastCompletedDeploy(projectName: String):Map[String,UUID] = Map.empty
  def addStringUUID(uuid: UUID) {}
  def getDeployUUIDsWithoutStringUUIDs: Iterable[SimpleDeployDetail] = Nil
  def summariseDeploy(uuid: UUID) {}
  def getCompleteDeploysOlderThan(dateTime: DateTime): Iterable[SimpleDeployDetail] = Nil
  def findProjects(): List[String] = Nil
  def addMetaData(uuid: UUID, metaData: Map[String, String]) {}
}

object DocumentStoreConverter extends Logging {
  val documentStore: DocumentStore = Persistence.store

  def saveDeploy(record: DeployRecord) {
    if (!record.messages.isEmpty) throw new IllegalArgumentException
    val converter = RecordConverter(record)
    documentStore.writeDeploy(converter.deployDocument)
    converter.logDocuments.foreach(documentStore.writeLog)
  }

  def saveMessage(message: MessageWrapper) {
    documentStore.writeLog(LogDocument(message))
  }

  def updateDeploySummary(record: DeployRecord) {
    updateDeploySummary(record.uuid, record.totalTasks, record.completedTasks, record.lastActivityTime)
  }

  def updateDeploySummary(uuid: UUID, totalTasks:Option[Int], completedTasks:Int, lastActivityTime:DateTime) {
    documentStore.updateDeploySummary(uuid, totalTasks, completedTasks, lastActivityTime)
  }

  def updateDeployStatus(record: DeployRecord) {
    updateDeployStatus(record.uuid, record.state)
  }

  def updateDeployStatus(uuid: UUID, state: RunState.Value) {
    documentStore.updateStatus(uuid, state)
  }

  def addMetaData(uuid: UUID, metaData: Map[String, String]) {
    documentStore.addMetaData(uuid, metaData)
  }

  def getDeployDocument(uuid:UUID) = documentStore.readDeploy(uuid)
  def getDeployLogs(uuid:UUID) = documentStore.readLogs(uuid)

  def getDeploy(uuid:UUID, fetchLog: Boolean = true): Option[DeployRecord] = {
    try {
      val deployDocument = getDeployDocument(uuid)
      val logDocuments = if (fetchLog) getDeployLogs(uuid) else Nil
      deployDocument.map { deploy =>
        DocumentConverter(deploy, logDocuments.toSeq).deployRecord
      }
    } catch {
      case e:Exception =>
        log.error(s"Couldn't get DeployRecord for $uuid", e)
        None
    }
  }

  def getDeployList(filter: Option[DeployFilter], pagination: PaginationView, fetchLog: Boolean = true): Seq[DeployRecord] = {
    documentStore.getDeploys(filter, pagination).toSeq.flatMap{ deployDocument =>
      try {
        val logs = if (fetchLog) getDeployLogs(deployDocument.uuid) else Nil
        Some(DocumentConverter(deployDocument, logs.toSeq).deployRecord)
      } catch {
        case e:Exception =>
          log.error(s"Couldn't get DeployRecord for ${deployDocument.uuid}", e)
          None
      }
    }
  }

  def countDeploys(filter: Option[DeployFilter]): Int = documentStore.countDeploys(filter)

  def getLastCompletedDeploys(project: String, fetchLog:Boolean = false): Map[String, DeployRecord] =
    documentStore.getLastCompletedDeploy(project).mapValues(uuid => getDeploy(uuid, fetchLog = fetchLog).get)

  def findProjects(): List[String] = documentStore.findProjects()
}