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

/**
  * A parameter for a deployment type
 *
  * @param name The name of the parameter that should be extracted from the parameter map (riff-raff.yaml) or data map
  *             (deploy.json)
  * @param documentation A documentation string (in markdown) that describes this parameter
  * @param optionalInYaml This can be set to true to make the parameter optional even when there are no defaults. This
  *                       might be needed if the value is not required to have a default or when only one of two
  *                       different parameters are specified.
  * @param defaultValue The default value for this parameter - used when a value is not found in the map
  * @param defaultValueFromContext A function that returns a defualt value for this parameter based on the package for
  *                                this deployment
  * @param register The parameter register - a Param self registers
  * @tparam T The type of the parameter to extract
  */
case class Param[T](
  name: String,
  documentation: String = "_undocumented_",
  optionalInYaml: Boolean = false,
  defaultValue: Option[T] = None,
  defaultValueFromContext: Option[(DeploymentPackage, DeployTarget) => Either[String,T]] = None
)(implicit register:ParamRegister) {
  register.add(this)

  val requiredInYaml = !optionalInYaml && defaultValue.isEmpty && defaultValueFromContext.isEmpty

  def get(pkg: DeploymentPackage)(implicit reads: Reads[T]): Option[T] =
    pkg.pkgSpecificData.get(name).flatMap(jsValue => Json.fromJson[T](jsValue).asOpt)
  def apply(pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter)(implicit reads: Reads[T], manifest: Manifest[T]): T = {
    val maybeValue = get(pkg)
    val defaultFromContext = defaultValueFromContext.map(_ (pkg, target))
    if (!pkg.legacyConfig) {
      val default = defaultValue.orElse(defaultFromContext.flatMap(_.right.toOption))
      if (default.isDefined && default == maybeValue)
        reporter.warning(s"Parameter $name is unnecessarily explicitly set to the default value of ${defaultValue.get}")
    }
    (maybeValue, defaultValue, defaultFromContext) match {
      case (Some(userDefined), _, _) => userDefined
      case (_, Some(default), _) => default
      case (_, _, Some(Right(contextDefault))) => contextDefault
      case (_, _, Some(Left(contextError))) =>
        throw new NoSuchElementException(
          s"Error whilst generating default for parameter $name in package ${pkg.name} [${pkg.deploymentTypeName}]: $contextError"
        )
      case _ =>
        throw new NoSuchElementException(
          s"Package ${pkg.name} [${pkg.deploymentTypeName}] requires parameter $name of type ${manifest.runtimeClass.getSimpleName}"
        )
    }
  }

  def default(default: T) = {
    this.copy(defaultValue = Some(default))
  }
  def defaultFromContext(defaultFromContext: (DeploymentPackage, DeployTarget) => Either[String,T]) = {
    this.copy(defaultValueFromContext = Some(defaultFromContext))
  }
}
