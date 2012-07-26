package deployment

import java.util.UUID
import magenta._
import magenta.MessageStack
import magenta.DeployParameters
import magenta.ReportTree

case class DeployParameterForm(project:String, build:String, stage:String)

case class DeploymentKey(stage:String, project:String, build:String, recipe:String = "default")

object Stages {
  lazy val list = List("CODE","DEV","QA","RELEASE","PROD","STAGE","TEST","INFRA").sorted
}

case class DeployRecord(uuid: UUID, parameters: DeployParameters, keyRing:KeyRing, messages: List[MessageStack] = Nil) {
  lazy val report:ReportTree = DeployReport(messages, "Deployment Report")
  lazy val project = parameters.build.name
  lazy val build = parameters.build.id
  lazy val stage = parameters.stage
  lazy val isRunning = report.isRunning
  def +(message: MessageStack): DeployRecord = {
    DeployRecord(uuid,parameters,keyRing,messages ++ List(message))
  }
}