package magenta

import magenta.artifact.S3Path
import magenta.deployment_type.DeploymentType
import play.api.libs.json.JsValue

case class DeploymentPackage(
  name: String,
  pkgApps: Seq[App],
  pkgSpecificData: Map[String, JsValue],
  deploymentTypeName: String,
  s3Package: S3Path) {

  def mkAction(name: String): Action = pkgType.mkAction(name)(this)

  lazy val pkgType = DeploymentType.all find (_.name == deploymentTypeName) getOrElse (
    throw new IllegalArgumentException(s"Package type $deploymentTypeName of package $name is unknown")
  )

  val apps = pkgApps
}