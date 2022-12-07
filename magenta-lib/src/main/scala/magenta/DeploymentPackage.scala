package magenta

import magenta.artifact.S3Path
import magenta.deployment_type.DeploymentType
import play.api.libs.json.JsValue

object DeploymentPackage {
  def apply(
      name: String,
      pkgApp: App,
      pkgSpecificData: Map[String, JsValue],
      deploymentTypeName: String,
      s3Package: S3Path,
      deploymentTypes: Seq[DeploymentType]
  ): DeploymentPackage = {
    val deploymentType =
      deploymentTypes find (_.name == deploymentTypeName) getOrElse (
        throw new IllegalArgumentException(
          s"Package type $deploymentTypeName of package $name is unknown"
        )
      )
    apply(name, pkgApp, pkgSpecificData, deploymentType, s3Package)
  }
}

case class DeploymentPackage(
    name: String,
    pkgApp: App,
    pkgSpecificData: Map[String, JsValue],
    deploymentType: DeploymentType,
    s3Package: S3Path
) {

  def mkDeploymentStep(action: String): DeploymentStep =
    deploymentType.mkDeploymentStep(action)(this)
  val app = pkgApp
}
