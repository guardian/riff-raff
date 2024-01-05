package deployment

import java.util.UUID
import com.gu.googleauth.UserIdentity
import magenta.ContextMessage._
import magenta.Message._
import magenta._
import org.joda.time.format.PeriodFormatterBuilder
import org.joda.time.{DateTime, Duration, Interval}
import utils.VCSInfo

sealed trait RequestSource
case class UserRequestSource(user: UserIdentity) extends RequestSource
case object ContinuousDeploymentRequestSource extends RequestSource
case object ScheduleRequestSource extends RequestSource

sealed trait RiffRaffError {
  val message: String
}

case class Error(message: String) extends RiffRaffError

sealed trait ScheduledDeployNotificationError extends RiffRaffError
case class NoDeploysFoundForStage(projectName: String, stage: String)
    extends ScheduledDeployNotificationError {
  val message =
    s"A scheduled deploy didn't start because Riff-Raff has never deployed $projectName to $stage before. " +
      "Please inform the owner of this schedule as it's likely that they have made a configuration error."
}
case class SkippedDueToPreviousPartialDeploy(partialDeployRecord: Record)
    extends ScheduledDeployNotificationError {
  val message =
    s"Scheduled Deployment for ${partialDeployRecord.parameters.build.projectName} to ${partialDeployRecord.parameters.stage.name} " +
      s"didn't start because the most recent deploy was 'partial' (i.e. had some deploy steps skipped). " +
      s"Please review the most recent deploy and manually deploy if it's now possible to deploy fully (all steps), " +
      s"to ensure instances are running with the latest AMI."
}
case class SkippedDueToPreviousFailure(failedDeployRecord: Record)
    extends ScheduledDeployNotificationError {
  val message =
    s"Scheduled Deployment for ${failedDeployRecord.parameters.build.projectName} to ${failedDeployRecord.parameters.stage.name} didn't start because the most recent deploy failed."
}
case class SkippedDueToPreviousWaitingDeploy(waitingDeployRecord: Record)
    extends ScheduledDeployNotificationError {
  val message =
    s"Scheduled Deployment for ${waitingDeployRecord.parameters.build.projectName} to ${waitingDeployRecord.parameters.stage.name} failed to start as a previous deploy was still waiting to be deployed."
}

object Record {
  val RIFFRAFF_HOSTNAME = "riffraff-hostname"
  val RIFFRAFF_DOMAIN = "riffraff-domain"

  private val formatter = new PeriodFormatterBuilder().appendDays
    .appendSuffix("d")
    .appendSeparator(" ")
    .appendHours
    .appendSuffix("h")
    .appendSeparator(" ")
    .appendMinutes
    .appendSuffix("m")
    .appendSeparator(" ")
    .appendSeconds
    .appendSuffix("s")
    .toFormatter

  def prettyPrintDuration(duration: Duration): String =
    formatter.print(duration.toPeriod)
}

trait Record {
  def time: DateTime
  def uuid: UUID
  def parameters: DeployParameters
  def metaData: Map[String, String]
  def report: DeployReport
  def recordState: Option[RunState]
  def recordTotalTasks: Option[Int]
  def recordCompletedTasks: Option[Int]
  def recordHasWarnings: Option[Boolean]

  lazy val buildName = parameters.build.projectName
  lazy val buildId = parameters.build.id
  lazy val deployerName = parameters.deployer.name
  lazy val stage = parameters.stage
  lazy val isRunning = report.isRunning
  lazy val isDone =
    (!isRunning && report.size > 1) || isSummarised || isMarkedAsFailed
  lazy val state = {
    recordState.getOrElse(
      report.cascadeState match {
        case RunState.ChildRunning => RunState.Running
        case other                 => other
      }
    )
  }
  def lastActivityTime: DateTime

  def timeTaken: Duration = new Duration(time, lastActivityTime)

  def isStalled: Boolean = {
    recordState.exists {
      case RunState.Running =>
        val stalledThreshold =
          new DateTime().minus(new Duration(15 * 60 * 1000))
        lastActivityTime.isBefore(stalledThreshold)
      case _ => false
    }
  }

  lazy val isMarkedAsFailed = recordState.exists {
    case RunState.Failed => true
    case _               => false
  }

  def isSummarised: Boolean

  lazy val hoursAgo: Long =
    new Interval(time, new DateTime()).toDuration.getStandardHours

  lazy val allMetaData = metaData ++ computedMetaData

  lazy val computedMetaData = vcsInfo.map(_.map).getOrElse(Map.empty)

  lazy val vcsInfo: Option[VCSInfo] = VCSInfo(metaData)

  lazy val totalTasks: Option[Int] = {
    if (isSummarised)
      recordTotalTasks
    else {
      val taskListMessages: Seq[TaskList] = report.allMessages.flatMap {
        case SimpleMessageState(list @ TaskList(tasks), _, _) => Some(list)
        case _                                                => None
      }
      assert(taskListMessages.size <= 1, "More than one TaskList in report")
      taskListMessages.headOption.map(_.taskList.size)
    }
  }

  lazy val completedTasks: Int = {
    if (isSummarised)
      recordCompletedTasks.getOrElse(0)
    else
      report.allMessages.count {
        case FinishMessageState(StartContext(TaskRun(task)), _, _, _) => true
        case _                                                        => false
      }
  }

  lazy val completedPercentage: Int =
    totalTasks
      .map { total =>
        (completedTasks * 100) / total
      }
      .getOrElse(0)

  lazy val hasWarnings: Boolean = if (isSummarised) {
    recordHasWarnings.getOrElse(false)
  } else {
    report.allMessages.exists { message =>
      message.message match {
        case Warning(_) => true
        case _          => false
      }
    }
  }
}

case class DeployRecord(
    time: DateTime,
    uuid: UUID,
    parameters: DeployParameters,
    metaData: Map[String, String] = Map.empty,
    messages: List[MessageWrapper] = Nil,
    recordState: Option[RunState] = None,
    recordTotalTasks: Option[Int] = None,
    recordCompletedTasks: Option[Int] = None,
    recordLastActivityTime: Option[DateTime] = None,
    recordHasWarnings: Option[Boolean] = None
) extends Record {
  lazy val report = DeployReport(messages)

  def +(message: MessageWrapper): DeployRecord = {
    this.copy(messages = messages ++ List(message))
  }

  def ++(newMetaData: Map[String, String]): DeployRecord = {
    this.copy(metaData = metaData ++ newMetaData)
  }

  def isSummarised = messages.isEmpty && recordState.isDefined

  lazy val lastActivityTime = messages.lastOption
    .map(_.stack.time)
    .orElse(recordLastActivityTime)
    .getOrElse(time)

}
