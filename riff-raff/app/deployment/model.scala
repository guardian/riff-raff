package deployment

import java.util.UUID
import magenta._
import magenta.MessageStack
import magenta.DeployParameters
import magenta.ReportTree
import java.io.File
import magenta.teamcity.Artifact.build2download

object Task extends Enumeration {
  type Type = Value
  val Deploy = Value("Deploy")
  val Preview = Value("Preview")
}

case class DeployRecord(taskType: Task.Type, uuid: UUID, parameters: DeployParameters, messages: List[MessageStack] = Nil, context:Option[DeployContext] = None) {
  lazy val report:ReportTree = DeployReport(messages, "Deployment Report")
  lazy val buildName = parameters.build.name
  lazy val buildId = parameters.build.id
  lazy val deployerName = parameters.deployer.name
  lazy val stage = parameters.stage
  lazy val isRunning = report.isRunning

  def +(message: MessageStack): DeployRecord = {
    this.copy(messages = messages ++ List(message))
  }
  def attachContext(project:Project): DeployRecord = {
    this.copy(context = Some(parameters.toDeployContext(project,DeployInfoManager.hostList)))
  }
  def loggingContext[T](block: => T): T = {
    MessageBroker.deployContext(uuid, parameters) { block }
  }
  def withDownload[T](block: File => T): T = {
    parameters.build.withDownload(block)
  }
}