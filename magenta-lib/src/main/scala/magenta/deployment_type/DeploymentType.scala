package magenta.deployment_type

import magenta._

import scala.collection.mutable

trait DeploymentType {
  def name: String
  def documentation: String

  private val registerParamsList = mutable.Map.empty[String, Param[_]]
  implicit val paramsRegister = new ParamRegister {
    def add(param: Param[_]) = {
      registerParamsList += param.name -> param
    }
  }
  def params = registerParamsList.values.toSeq

  private val registerActionsMap = mutable.Map.empty[String, Action]
  implicit val actionsRegister = new ActionRegister {
    def add(action: Action) = {
      registerActionsMap += action.name -> action
    }
  }
  def actionsMap = registerActionsMap.toMap

  def defaultActions: List[Action]
  def defaultActionNames = defaultActions.map(_.name)

  def mkActionResolver(actionName: String)(pkg: DeploymentPackage): ActionResolver = {
    actionsMap.lift(actionName).map { action =>
      new PackageAction(pkg, actionName) {
        def resolve(resources: DeploymentResources, target: DeployTarget) =
          action.taskGenerator(pkg, resources, target)
      }
    } getOrElse sys.error(s"Action $actionName is not supported on package ${pkg.name} of type $name")
  }

  abstract case class PackageAction(pkg: DeploymentPackage, actionName: String) extends ActionResolver {
    def apps = pkg.apps
    def description = pkg.name + "." + actionName
  }
}