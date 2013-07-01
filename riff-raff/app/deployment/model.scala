package deployment

import java.util.UUID
import magenta._
import magenta.DeployParameters
import magenta.ReportTree
import java.io.File
import org.joda.time.{Interval, DateTime, Duration}
import ci.ContinuousIntegration
import utils.VCSInfo
import magenta.teamcity.Artifact
import conf.Configuration

object TaskType extends Enumeration {
  val Deploy = Value("Deploy")
  val Preview = Value("Preview")
}

trait Record {
  def time: DateTime
  def taskType: TaskType.Value
  def uuid: UUID
  def parameters: DeployParameters
  def metaData: Map[String, String]
  def report: ReportTree
  def recordState: Option[RunState.Value]

  lazy val buildName = parameters.build.projectName
  lazy val buildId = parameters.build.id
  lazy val deployerName = parameters.deployer.name
  lazy val stage = parameters.stage
  lazy val recipe = parameters.recipe
  lazy val isRunning = report.isRunning
  lazy val isDone = (!isRunning && report.size > 1) || isSummarised || isMarkedAsFailed
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

  lazy val isMarkedAsFailed = recordState.map {
    case RunState.Failed => true
    case _ => false
  }.getOrElse(false)

  def isSummarised: Boolean

  def loggingContext[T](block: => T): T = {
    MessageBroker.deployContext(uuid, parameters) { block }
  }
  def withDownload[T](block: File => T): T = {
    Artifact.withDownload(Configuration.teamcity.serverURL, parameters.build)(block)
  }

  lazy val hoursAgo: Long = new Interval(time, new DateTime()).toDuration.getStandardHours

  lazy val allMetaData = metaData ++ computedMetaData

  lazy val computedMetaData = vcsInfo.map(_.map).getOrElse(Map.empty)

  lazy val vcsInfo: Option[VCSInfo] = VCSInfo(metaData)
}

object DeployV2Record {
  def apply(taskType: TaskType.Value,
            uuid: UUID,
            parameters: DeployParameters ): DeployV2Record = {
    val metaData = ContinuousIntegration.getMetaData(parameters.build.projectName, parameters.build.id)
    DeployV2Record(new DateTime(), taskType, uuid, parameters, metaData)
  }
}

case class DeployV2Record(time: DateTime,
                          taskType: TaskType.Value,
                           uuid: UUID,
                           parameters: DeployParameters,
                           metaData: Map[String, String] = Map.empty,
                           messages: List[MessageWrapper] = Nil,
                           recordState: Option[RunState.Value] = None) extends Record {
  lazy val report = DeployReport.v2(messages, "Deployment Report")

  def +(message: MessageWrapper): DeployV2Record = {
    this.copy(messages = messages ++ List(message))
  }

  def ++(newMetaData: Map[String, String]): DeployV2Record = {
    this.copy(metaData = metaData ++ newMetaData)
  }

  def isSummarised = messages.isEmpty && recordState.isDefined

  lazy val lastActivityTime = messages.lastOption.map(_.stack.time).getOrElse(time)

}