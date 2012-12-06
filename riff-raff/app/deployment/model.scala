package deployment

import java.util.UUID
import magenta._
import magenta.MessageStack
import magenta.DeployParameters
import magenta.ReportTree
import java.io.File
import magenta.teamcity.Artifact.build2download
import org.joda.time.DateTime

object Task extends Enumeration {
  val Deploy = Value("Deploy")
  val Preview = Value("Preview")
}

trait Record {
  def time: DateTime
  def taskType: Task.Value
  def uuid: UUID
  def parameters: DeployParameters
  def report: ReportTree
  def recordState: Option[RunState.Value]

  lazy val buildName = parameters.build.projectName
  lazy val buildId = parameters.build.id
  lazy val deployerName = parameters.deployer.name
  lazy val stage = parameters.stage
  lazy val recipe = parameters.recipe
  lazy val isRunning = report.isRunning
  lazy val isDone = !isRunning && report.size > 1
  lazy val state = {
    recordState.getOrElse(
      report.cascadeState match {
        case RunState.ChildRunning => RunState.Running
        case other => other
      }
    )
  }

  def isSummarised: Boolean

  def loggingContext[T](block: => T): T = {
    MessageBroker.deployContext(uuid, parameters) { block }
  }
  def withDownload[T](block: File => T): T = {
    parameters.build.withDownload(block)
  }
}

object DeployV2Record {
  def apply(taskType: Task.Value,
            uuid: UUID,
            parameters: DeployParameters ): DeployV2Record = {
    DeployV2Record(new DateTime(), taskType, uuid, parameters)
  }
}

case class DeployV2Record(time: DateTime,
                          taskType: Task.Value,
                           uuid: UUID,
                           parameters: DeployParameters,
                           messages: List[MessageWrapper] = Nil,
                           recordState: Option[RunState.Value] = None) extends Record {
  lazy val report = DeployReport.v2(messages, "Deployment Report")

  def +(message: MessageWrapper): DeployV2Record = {
    this.copy(messages = messages ++ List(message))
  }

  def isSummarised = messages.isEmpty && recordState.isDefined
}

object DeployRecord {
  def apply(taskType: Task.Value,
            uuid: UUID,
            parameters: DeployParameters ): DeployRecord = {
    DeployRecord(new DateTime(), taskType, uuid, parameters)
  }
}

case class DeployRecord(time: DateTime,
                        taskType: Task.Value,
                        uuid: UUID,
                        parameters: DeployParameters,
                        messageStacks: List[MessageStack] = Nil) extends Record {
  lazy val report:ReportTree = DeployReport(messageStacks, "Deployment Report")

  def +(message: MessageStack): DeployRecord = {
    this.copy(messageStacks = messageStacks ++ List(message))
  }

  lazy val recordState = None

  def isSummarised = false
}