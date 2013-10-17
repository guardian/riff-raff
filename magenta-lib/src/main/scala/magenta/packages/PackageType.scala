package magenta.packages

import magenta._
import magenta.json.JValueExtractable
import magenta.tasks.Task
import magenta.DeployParameters
import magenta.Host
import magenta.Package

trait PackageType {
  def name: String
  def params: Seq[Param[_]]
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

object PackageType {
  def all: Seq[PackageType] = Seq(
    ElasticSearch, S3, AutoScaling, ExecutableJarWebapp, JettyWebapp, ResinWebapp, FileCopy, Django, Fastly, UnzipToDocroot
  )
}

case class Param[T](name: String, default: Option[T] = None, defaultFromPackage: Package => Option[T] = (_: Package) => None) {
  def get(pkg: Package)(implicit extractable: JValueExtractable[T]): Option[T] =
    pkg.pkgSpecificData.get(name).flatMap(extractable.extract(_))
  def apply(pkg: Package)(implicit extractable: JValueExtractable[T]): T =
    get(pkg).orElse(default).orElse(defaultFromPackage(pkg)).getOrElse(throw new NoSuchElementException())
}
