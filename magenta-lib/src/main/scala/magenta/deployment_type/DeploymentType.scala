package magenta.deployment_type

import magenta._
import magenta.json.JValueExtractable
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
  def perAppActions: PartialFunction[String, DeploymentPackage =>
    (DeployReporter, Lookup, DeployParameters, Stack) => List[Task]]
  def perHostActions: PartialFunction[String, DeploymentPackage =>
    (DeployReporter, Host, KeyRing) => List[Task]] = PartialFunction.empty

  def mkAction(actionName: String)(pkg: DeploymentPackage): Action = {
    perHostActions.lift(actionName).map { action =>
      new PackageAction(pkg, actionName)  {
        def resolve(resourceLookup: Lookup, parameters: DeployParameters, stack: Stack, deployReporter: DeployReporter) = {
          val hostsForApps = apps.toList.flatMap { app =>
            resourceLookup.hosts.get(pkg, app, parameters, stack)
          } filter { instance =>
            parameters.matchingHost(instance.name)
          }
          hostsForApps flatMap (action(pkg)(deployReporter, _,
            resourceLookup.keyRing(parameters.stage, pkg.apps.toSet, stack)))
        }
      }
    } orElse perAppActions.lift(actionName).map { action =>
      new PackageAction(pkg, actionName) {
        def resolve(resourceLookup: Lookup, parameters: DeployParameters, stack: Stack, deployReporter: DeployReporter) =
          action(pkg)(deployReporter, resourceLookup, parameters, stack)
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
    ElasticSearch, S3, AutoScaling, ExecutableJarWebapp, JettyWebapp, FileCopy, Django, Fastly,
    CloudFormation, RPM, NativePackagerWebapp, Lambda, AmiCloudFormationParameter, SelfDeploy
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

  def get(pkg: DeploymentPackage)(implicit extractable: JValueExtractable[T]): Option[T] =
    pkg.pkgSpecificData.get(name).flatMap(extractable.extract(_))
  def apply(pkg: DeploymentPackage)(implicit extractable: JValueExtractable[T], manifest: Manifest[T]): T =
    get(pkg).orElse(defaultValue).orElse(defaultValueFromPackage.map(_(pkg))).getOrElse{
      throw new NoSuchElementException(
        s"Package ${pkg.name} [${pkg.deploymentTypeName}] requires parameter $name of type ${manifest.runtimeClass.getSimpleName}"
      )
    }

  def default(default: T) = {
    this.copy(defaultValue = Some(default))
  }
  def defaultFromPackage(defaultFromPackage: DeploymentPackage => T) = {
    this.copy(defaultValueFromPackage = Some((p: DeploymentPackage) => defaultFromPackage(p)))
  }
}
