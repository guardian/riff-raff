package magenta.deployment_type

import magenta._
import magenta.json.JValueExtractable
import magenta.tasks.Task
import magenta.DeployParameters
import magenta.Host
import magenta.Package
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
  def perAppActions: PartialFunction[String, Package => (DeployInfo, DeployParameters) => List[Task]]
  def perHostActions: PartialFunction[String, Package => Host => List[Task]] = PartialFunction.empty

  def mkAction(actionName: String)(pkg: Package): Action = {

    if (perHostActions.isDefinedAt(actionName))
      new PackageAction(pkg, actionName)  {
        def resolve(deployInfo: DeployInfo, parameters: DeployParameters) = {
          val hostsForApps = deployInfo.hosts.filter(h => (h.apps intersect apps).nonEmpty)
          hostsForApps flatMap (perHostActions(actionName)(pkg)(_))
        }
      }

    else if (perAppActions.isDefinedAt(actionName))
      new PackageAction(pkg, actionName) {
        def resolve(deployInfo: DeployInfo, parameters: DeployParameters) =
          perAppActions(actionName)(pkg)(deployInfo, parameters)
      }

    else sys.error("Action %s is not supported on package %s of type %s" format (actionName, pkg.name, name))
  }

  abstract case class PackageAction(pkg: Package, actionName: String) extends Action {
    def apps = pkg.apps
    def description = pkg.name + "." + actionName
  }
}

object DeploymentType {
  def all: Seq[DeploymentType] = Seq(
    ElasticSearch, S3, AutoScaling, ExecutableJarWebapp, JettyWebapp, ResinWebapp, FileCopy, Django, Fastly, UnzipToDocroot
  )
}

trait ParamRegister {
  def add(param: Param[_])
}

case class Param[T](name: String,
                    documentation: String = "_undocumented_",
                    defaultValue: Option[T] = None,
                    defaultValueFromPackage: Option[Package => T] = None)(implicit register:ParamRegister) {
  register.add(this)

  def get(pkg: Package)(implicit extractable: JValueExtractable[T]): Option[T] =
    pkg.pkgSpecificData.get(name).flatMap(extractable.extract(_))
  def apply(pkg: Package)(implicit extractable: JValueExtractable[T]): T =
    get(pkg).orElse(defaultValue).orElse(defaultValueFromPackage.map(_(pkg))).getOrElse(throw new NoSuchElementException())

  def default(default: T) = {
    this.copy(defaultValue = Some(default))
  }
  def defaultFromPackage(defaultFromPackage: Package => T) = {
    this.copy(defaultValueFromPackage = Some((p: Package) => defaultFromPackage(p)))
  }
}
