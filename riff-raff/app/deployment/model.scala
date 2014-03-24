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

object Record {
  val RIFFRAFF_HOSTNAME = "riffraff-hostname"
  val RIFFRAFF_DOMAIN = "riffraff-domain"
}

trait Record {
  def time: DateTime
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
    recordState.exists {
      case RunState.Running =>
        val stalledThreshold = (new DateTime()).minus(new Duration(15 * 60 * 1000))
        lastActivityTime.isBefore(stalledThreshold)
      case _ => false
    }
  }

  lazy val isMarkedAsFailed = recordState.exists {
    case RunState.Failed => true
    case _ => false
  }

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

  lazy val totalTasks: Option[Int] = {
    val taskListMessages:Seq[TaskList] = report.allMessages.flatMap{
      case SimpleMessageState(list@TaskList(tasks), _, _) => Some(list)
      case _ => None
    }
    assert(taskListMessages.size <= 1, "More than one TaskList in report")
    taskListMessages.headOption.map(_.taskList.size)
  }

  lazy val completedTasks: Int = {
    report.allMessages.count {
      case FinishMessageState(StartContext(TaskRun(task)), _, _, _) => true
      case _ => false
    }
  }

  lazy val completedPercentage: Int =
    totalTasks.map{total =>
      (completedTasks * 100) / total
    }.getOrElse(0)
}

object DeployV2Record {
  def apply(uuid: UUID,
            parameters: DeployParameters ): DeployV2Record = {
    val metaData = ContinuousIntegration.getMetaData(parameters.build.projectName, parameters.build.id)
    DeployV2Record(new DateTime(), uuid, parameters, metaData)
  }
}

case class DeployV2Record(time: DateTime,
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