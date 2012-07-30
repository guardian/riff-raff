package deployment

import java.util.UUID
import magenta._
import magenta.MessageStack
import magenta.DeployParameters
import magenta.ReportTree
import java.io.File
import magenta.teamcity.Artifact.build2download

case class DeployParameterForm(project:String, build:String, stage:String, action: String)

case class DeploymentKey(stage:String, project:String, build:String, recipe:String = "default")

object Stages {
  lazy val list = List("CODE","DEV","QA","RELEASE","PROD","STAGE","TEST","INFRA").sorted
}

object Task extends Enumeration {
  type Type = Value
  val Deploy = Value("Deploy")
  val Preview = Value("Preview")
}

case class DeployRecord(taskType: Task.Type, uuid: UUID, parameters: DeployParameters, keyRing:KeyRing, messages: List[MessageStack] = Nil, context:Option[DeployContext] = None) {
  lazy val report:ReportTree = DeployReport(messages, "Deployment Report")
  lazy val buildName = parameters.build.name
  lazy val buildId = parameters.build.id
  lazy val stage = parameters.stage
  lazy val isRunning = report.isRunning

  def +(message: MessageStack): DeployRecord = {
    this.copy(messages = messages ++ List(message))
  }
  def attachContext(project:Project): DeployRecord = {
    this.copy(context = Some(parameters.toDeployContext(project,DeployInfo.hostList)))
  }
  def loggingContext[T](block: => T): T = {
    MessageBroker.withUUID(uuid) { block }
  }
  def withDownload[T](block: File => T): T = {
    parameters.build.withDownload(block)
  }
}