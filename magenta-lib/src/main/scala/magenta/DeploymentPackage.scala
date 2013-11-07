package magenta

import java.io.File
import net.liftweb.json.JsonAST._
import magenta.deployment_type.DeploymentType

case class DeploymentPackage(
  name: String,
  pkgApps: Set[App],
  pkgSpecificData: Map[String, JValue],
  deploymentTypeName: String,
  srcDir: File) {

  def mkAction(name: String): Action = pkgType.mkAction(name)(this)

  lazy val pkgType = DeploymentType.all find (_.name == deploymentTypeName) getOrElse (
    throw new IllegalArgumentException(s"Package type $deploymentTypeName of package $name is unknown")
  )

  val apps = pkgApps
}