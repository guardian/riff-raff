package persistence

import java.util.UUID
import cats.instances.either._
import cats.instances.list._
import cats.syntax.traverse._
import controllers.{Logging, SimpleDeployDetail}
import deployment.{DeployFilter, DeployRecord, PaginationView}
import magenta.ContextMessage._
import magenta.Strategy.MostlyHarmless
import magenta._
import magenta.input.{All, DeploymentKey, DeploymentKeysSelector}
import org.joda.time.DateTime
import persistence.DeploymentSelectorDocument._

case class RecordConverter(
    uuid: UUID,
    startTime: DateTime,
    params: ParametersDocument,
    status: RunState,
    messages: List[MessageWrapper] = Nil
) extends Logging {
  def +(newWrapper: MessageWrapper): RecordConverter =
    copy(messages = messages ::: List(newWrapper))
  def +(newStatus: RunState): RecordConverter = copy(status = newStatus)

  def apply(message: MessageWrapper): Option[LogDocument] = {
    val stackId = message.messageId
    logDocuments.find(_.id == stackId)
  }

  def apply: (DeployRecordDocument, Seq[LogDocument]) =
    (deployDocument, logDocuments)

  lazy val deployDocument =
    DeployRecordDocument(uuid, Some(uuid.toString), startTime, params, status)

  lazy val logDocuments = {
    val logDocumentSeq: Seq[LogDocument] = messages.map(LogDocument(_))
    val ids = logDocumentSeq.map(_.id)
    if (ids.size != ids.toSet.size)
      log.error(s"Key collision detected in log of deploy $uuid")
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
      tags = record.metaData,
      selector = sourceParams.selector match {
        case All                         => AllDocument
        case DeploymentKeysSelector(ids) =>
          DeploymentKeysSelectorDocument(
            ids.map(id =>
              DeploymentKeyDocument(
                name = id.name,
                action = id.action,
                stack = id.stack,
                region = id.region
              )
            )
          )
      },
      updateStrategy = Some(sourceParams.updateStrategy)
    )
    RecordConverter(
      record.uuid,
      record.time,
      params,
      record.state,
      record.messages
    )
  }
}

case class DocumentConverter(
    deploy: DeployRecordDocument,
    logs: Seq[LogDocument]
) {

  lazy val parameters = DeployParameters(
    Deployer(deploy.parameters.deployer),
    Build(deploy.parameters.projectName, deploy.parameters.buildId),
    Stage(deploy.parameters.stage),
    deploy.parameters.selector match {
      case AllDocument                          => All
      case DeploymentKeysSelectorDocument(keys) =>
        DeploymentKeysSelector(
          keys.map(key =>
            DeploymentKey(
              name = key.name,
              action = key.action,
              stack = key.stack,
              region = key.region
            )
          )
        )
    },
    deploy.parameters.updateStrategy.getOrElse(MostlyHarmless)
  )

  lazy val deployRecord =
    DeployRecord(
      deploy.startTime,
      deploy.uuid,
      parameters,
      deploy.parameters.tags,
      messageWrappers,
      Some(deploy.status),
      deploy.totalTasks,
      deploy.completedTasks,
      deploy.lastActivityTime,
      deploy.hasWarnings
    )

  lazy val messageWrappers: List[MessageWrapper] = {
    if (logs.isEmpty) Nil else convertToMessageWrappers(LogDocumentTree(logs))
  }

  def convertToMessageWrappers(tree: LogDocumentTree): List[MessageWrapper] =
    convertToMessageWrappers(tree, tree.roots.head)

  def convertToMessageWrappers(
      tree: LogDocumentTree,
      log: LogDocument,
      messagesTail: List[Message] = Nil
  ): List[MessageWrapper] = {
    val children = tree.childrenOf(log).toList
    log.document match {
      case leaf if children.isEmpty =>
        List(
          messageWrapper(
            log,
            MessageStack(
              leaf
                .asMessage(parameters, messagesTail.headOption) :: messagesTail,
              log.time
            )
          )
        )
      case node =>
        val message: Message = node.asMessage(parameters)
        messageWrapper(
          log,
          MessageStack(StartContext(message) :: messagesTail, log.time)
        ) ::
          children.flatMap(child =>
            convertToMessageWrappers(tree, child, message :: messagesTail)
          )
    }
  }

  def messageWrapper(log: LogDocument, stack: MessageStack): MessageWrapper = {
    MessageWrapper(
      MessageContext(log.deploy, parameters, log.parent),
      log.id,
      stack
    )
  }
}

trait DocumentStore {
  def writeDeploy(deploy: DeployRecordDocument): Unit
  def writeLog(log: LogDocument): Unit
  def updateStatus(uuid: UUID, status: RunState): Unit
  def updateDeploySummary(
      uuid: UUID,
      totalTasks: Option[Int],
      completedTasks: Int,
      lastActivityTime: DateTime,
      hasWarnings: Boolean
  ): Unit
  def readDeploy(uuid: UUID): Option[DeployRecordDocument] = None
  def readLogs(uuid: UUID): List[LogDocument] = Nil
  def getDeployUUIDs(limit: Int = 0): List[SimpleDeployDetail] = Nil
  def getDeploys(
      filter: Option[DeployFilter],
      pagination: PaginationView
  ): Either[Throwable, List[DeployRecordDocument]] = Right(Nil)
  def countDeploys(filter: Option[DeployFilter]): Int = 0
  def deleteDeployLog(uuid: UUID): Unit
  def getLastCompletedDeploys(projectName: String): Map[String, UUID] =
    Map.empty
  def addStringUUID(uuid: UUID): Unit = {}
  def getDeployUUIDsWithoutStringUUIDs: List[SimpleDeployDetail] = Nil
  def summariseDeploy(uuid: UUID): Unit = {}
  def getCompleteDeploysOlderThan(
      dateTime: DateTime
  ): List[SimpleDeployDetail] = Nil
  def findProjects: Either[Throwable, List[String]]
  def addMetaData(uuid: UUID, metaData: Map[String, String]): Unit
}

class DocumentStoreConverter(datastore: DataStore) extends Logging {

  def saveDeploy(record: DeployRecord): Unit = {
    if (record.messages.nonEmpty) throw new IllegalArgumentException
    val converter = RecordConverter(record)
    datastore.writeDeploy(converter.deployDocument)
    converter.logDocuments.foreach(datastore.writeLog)
  }

  def saveMessage(message: MessageWrapper): Unit = {
    datastore.writeLog(LogDocument(message))
  }

  def updateDeploySummary(record: DeployRecord): Unit = {
    updateDeploySummary(
      record.uuid,
      record.totalTasks,
      record.completedTasks,
      record.lastActivityTime,
      record.hasWarnings
    )
  }

  def updateDeploySummary(
      uuid: UUID,
      totalTasks: Option[Int],
      completedTasks: Int,
      lastActivityTime: DateTime,
      hasWarnings: Boolean
  ): Unit = {
    datastore.updateDeploySummary(
      uuid,
      totalTasks,
      completedTasks,
      lastActivityTime,
      hasWarnings
    )
  }

  def updateDeployStatus(record: DeployRecord): Unit = {
    updateDeployStatus(record.uuid, record.state)
  }

  def updateDeployStatus(uuid: UUID, state: RunState): Unit = {
    datastore.updateStatus(uuid, state)
  }

  def addMetaData(uuid: UUID, metaData: Map[String, String]): Unit = {
    datastore.addMetaData(uuid, metaData)
  }

  def getDeployDocument(uuid: UUID) = datastore.readDeploy(uuid)
  def getDeployLogs(uuid: UUID) =
    datastore.readLogs(uuid).distinctOn(log => (log.deploy, log.id))

  def getDeploy(uuid: UUID, fetchLog: Boolean = true): Option[DeployRecord] = {
    try {
      val deployDocument = getDeployDocument(uuid)
      val logDocuments = if (fetchLog) getDeployLogs(uuid) else Nil
      deployDocument.map { deploy =>
        DocumentConverter(deploy, logDocuments.toSeq).deployRecord
      }
    } catch {
      case e: Exception =>
        log.error(s"Couldn't get DeployRecord for $uuid", e)
        None
    }
  }

  def getDeployList(
      filter: Option[DeployFilter],
      pagination: PaginationView,
      fetchLog: Boolean = true
  ): Either[Throwable, List[DeployRecord]] = {
    datastore.getDeploys(filter, pagination).flatMap { records =>
      records.traverse { deployDocument =>
        try {
          val logs = if (fetchLog) getDeployLogs(deployDocument.uuid) else Nil
          Right(DocumentConverter(deployDocument, logs).deployRecord)
        } catch {
          case e: Exception =>
            log
              .error(s"Couldn't get DeployRecord for ${deployDocument.uuid}", e)
            Left(e)
        }
      }
    }
  }

  def countDeploys(filter: Option[DeployFilter]): Int =
    datastore.countDeploys(filter)

  def getLastCompletedDeploys(
      project: String,
      fetchLog: Boolean = false
  ): Map[String, DeployRecord] =
    datastore
      .getLastCompletedDeploys(project)
      .view
      .mapValues(uuid => getDeploy(uuid, fetchLog = fetchLog).get)
      .toMap

  def findProjects: Either[Throwable, List[String]] = datastore.findProjects
}
