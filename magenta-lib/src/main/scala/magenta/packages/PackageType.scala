package magenta.packages

import magenta.{Host, DeployParameters, DeployInfo, Package}
import magenta.json.JValueExtractable
import magenta.tasks.Task

trait PackageType {
  def name: String
  def params: Seq[Param[_]]
  def perAppActions: PartialFunction[String, Package => (DeployInfo, DeployParameters) => List[Task]]
  def perHostActions: PartialFunction[String, Package => Host => List[Task]] = PartialFunction.empty
}

object PackageType {
  def all: Seq[PackageType] = Seq(ElasticSearch)
}


case class Param[T](name: String, default: Option[T] = None) {
  def get(pkg: Package)(implicit extractable: JValueExtractable[T]): Option[T] =
    pkg.pkgSpecificData.get(name).flatMap(extractable.extract(_))
  def apply(pkg: Package)(implicit extractable: JValueExtractable[T]): T =
    get(pkg).orElse(default).getOrElse(throw new NoSuchElementException())
}
