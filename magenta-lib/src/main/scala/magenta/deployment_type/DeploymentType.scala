package magenta.deployment_type

import magenta._
import magenta.tasks.Task
import play.api.libs.json.{Json, Reads}

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

object DeploymentType {
  def all: Seq[DeploymentType] = Seq(
    ElasticSearch, S3, AutoScaling, Fastly, CloudFormation, Lambda, AmiCloudFormationParameter, SelfDeploy
  )
}

trait ParamRegister {
  def add(param: Param[_])
}

case class Param[T](name: String,
                    documentation: String = "_undocumented_",
                    defaultValue: Option[T] = None,
                    defaultValueFromPackage: Option[DeploymentPackage => T] = None)(implicit register:ParamRegister) {
  register.add(this)

  def get(pkg: DeploymentPackage)(implicit reads: Reads[T]): Option[T] =
    pkg.pkgSpecificData.get(name).flatMap(jsValue => Json.fromJson[T](jsValue).asOpt)
  def apply(pkg: DeploymentPackage)(implicit reporter: DeployReporter, reads: Reads[T], manifest: Manifest[T]): T = {
    val maybeValue = get(pkg)
    val maybeDefault = defaultValue.orElse(defaultValueFromPackage.map(_ (pkg)))
    if (!pkg.legacyConfig && maybeDefault.isDefined && maybeValue == maybeDefault) {
      reporter.warning(s"Parameter $name is unnecessarily set to the default value of ${defaultValue.get}")
    }
    maybeValue.orElse(maybeDefault).getOrElse {
      throw new NoSuchElementException(
        s"Package ${pkg.name} [${pkg.deploymentTypeName}] requires parameter $name of type ${manifest.runtimeClass.getSimpleName}"
      )
    }
  }

  def default(default: T) = {
    this.copy(defaultValue = Some(default))
  }
  def defaultFromPackage(defaultFromPackage: DeploymentPackage => T) = {
    this.copy(defaultValueFromPackage = Some((p: DeploymentPackage) => defaultFromPackage(p)))
  }
}
