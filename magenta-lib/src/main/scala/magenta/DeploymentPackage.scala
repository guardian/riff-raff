package magenta

import magenta.artifact.S3Path
import magenta.deployment_type.DeploymentType
import play.api.libs.json.JsValue

object DeploymentPackage {
  def apply(name: String,
    pkgApps: Seq[App],
    pkgSpecificData: Map[String, JsValue],
    deploymentTypeName: String,
    s3Package: S3Path,
    legacyConfig: Boolean,
    deploymentTypes: Seq[DeploymentType]
  ): DeploymentPackage = {
    val deploymentType = deploymentTypes find (_.name == deploymentTypeName) getOrElse (
      throw new IllegalArgumentException(s"Package type $deploymentTypeName of package $name is unknown")
    )
    apply(name, pkgApps, pkgSpecificData, deploymentType, s3Package, legacyConfig)
  }
}

case class DeploymentPackage(
  name: String,
  pkgApps: Seq[App],
  pkgSpecificData: Map[String, JsValue],
  deploymentType: DeploymentType,
  s3Package: S3Path,
  legacyConfig: Boolean) {

  def mkAction(name: String): ActionResolver = deploymentType.mkActionResolver(name)(this)
  val apps = pkgApps
}