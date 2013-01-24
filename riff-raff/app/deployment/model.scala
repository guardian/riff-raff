package deployment

import java.util.UUID
import magenta._
import magenta.MessageStack
import magenta.DeployParameters
import magenta.ReportTree
import java.io.File
import magenta.teamcity.Artifact.build2download
import org.joda.time.{Period, Interval, DateTime, Duration}

object TaskType extends Enumeration {
  val Deploy = Value("Deploy")
  val Preview = Value("Preview")
}

trait Record {
  def time: DateTime
  def taskType: TaskType.Value
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
  lazy val isDone = (!isRunning && report.size > 1) || isSummarised
  lazy val state = {
    recordState.getOrElse(
      report.cascadeState match {
        case RunState.ChildRunning => RunState.Running
        case other => other
      }
    )
  }
  def lastActivityTime: DateTime

  def isStalled: Boolean = {
    recordState.map { state =>
      state match {
        case RunState.Running =>
          val stalledThreshold = (new DateTime()).minus(new Duration(15*60*1000))
          lastActivityTime.isBefore(stalledThreshold)
        case _ => false
      }
    }.getOrElse(false)
  }

  def isSummarised: Boolean

  def loggingContext[T](block: => T): T = {
    MessageBroker.deployContext(uuid, parameters) { block }
  }
  def withDownload[T](block: File => T): T = {
    parameters.build.withDownload(block)
  }

  lazy val hoursAgo: Long = new Interval(time, new DateTime()).toDuration.getStandardHours
}

object DeployV2Record {
  def apply(taskType: TaskType.Value,
            uuid: UUID,
            parameters: DeployParameters ): DeployV2Record = {
    DeployV2Record(new DateTime(), taskType, uuid, parameters)
  }
}

case class DeployV2Record(time: DateTime,
                          taskType: TaskType.Value,
                           uuid: UUID,
                           parameters: DeployParameters,
                           messages: List[MessageWrapper] = Nil,
                           recordState: Option[RunState.Value] = None) extends Record {
  lazy val report = DeployReport.v2(messages, "Deployment Report")

  def +(message: MessageWrapper): DeployV2Record = {
    this.copy(messages = messages ++ List(message))
  }

  def isSummarised = messages.isEmpty && recordState.isDefined

  lazy val lastActivityTime = messages.lastOption.map(_.stack.time).getOrElse(time)

}