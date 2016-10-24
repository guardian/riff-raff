package magenta.deployment_type

import magenta._
import magenta.tasks.Task

import scala.collection.mutable

trait DeploymentType {
  def name: String
  def documentation: String

  implicit val register = new ParamRegister {
    def add(param: Param[_]) = {
      paramsList += param.name -> param
    }
  }
  val paramsList = mutable.Map.empty[String, Param[_]]
  lazy val params = paramsList.values.toSeq
  def actions: PartialFunction[String, DeploymentPackage => (DeploymentResources, DeployTarget) => List[Task]]
  def defaultActions: List[String]

  def mkAction(actionName: String)(pkg: DeploymentPackage): Action = {
    actions.lift(actionName).map { action =>
      new PackageAction(pkg, actionName) {
        def resolve(resources: DeploymentResources, target: DeployTarget) =
          action(pkg)(resources, target)
      }
    } getOrElse sys.error(s"Action $actionName is not supported on package ${pkg.name} of type $name")
  }

  abstract case class PackageAction(pkg: DeploymentPackage, actionName: String) extends Action {
    def apps = pkg.apps
    def description = pkg.name + "." + actionName
  }
}