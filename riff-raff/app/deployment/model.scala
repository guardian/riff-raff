package deployment

case class DeployParameterForm(project:String, build:String, stage:String)

case class DeploymentKey(stage:String, project:String, build:String, recipe:String = "default")

object Stages {
  lazy val list = List("CODE","DEV","QA","RELEASE","PROD","STAGE","TEST","INFRA").sorted
}