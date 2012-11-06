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
                        messageStacks: List[MessageStack] = Nil) {
  lazy val report:ReportTree = DeployReport(messageStacks, "Deployment Report")
  lazy val buildName = parameters.build.projectName
  lazy val buildId = parameters.build.id
  lazy val deployerName = parameters.deployer.name
  lazy val stage = parameters.stage
  lazy val recipe = parameters.recipe
  lazy val isRunning = report.isRunning
  lazy val isDone = !isRunning && report.size > 1

  def +(message: MessageStack): DeployRecord = {
    this.copy(messageStacks = messageStacks ++ List(message))
  }
  def loggingContext[T](block: => T): T = {
    MessageBroker.deployContext(uuid, parameters) { block }
  }
  def withDownload[T](block: File => T): T = {
    parameters.build.withDownload(block)
  }
}