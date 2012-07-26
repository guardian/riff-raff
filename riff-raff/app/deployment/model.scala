package deployment

import java.util.UUID
import magenta.{DeployParameters, MessageStack}

case class DeployParameterForm(project:String, build:String, stage:String)

case class DeploymentKey(stage:String, project:String, build:String, recipe:String = "default")

object Stages {
  lazy val list = List("CODE","DEV","QA","RELEASE","PROD","STAGE","TEST","INFRA").sorted
}

case class DeployRecord(uuid: UUID, params: DeployParameters, messages: List[MessageStack] = Nil) {
}