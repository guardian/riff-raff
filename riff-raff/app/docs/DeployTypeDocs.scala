package docs

import magenta.artifact.S3Path
import magenta.{App, Build, DeployParameters, DeployTarget, Deployer, DeploymentPackage, NamedStack, RecipeName, Region, Stage}
import magenta.deployment_type.{DeploymentType, Param}

object DeployTypeDocs {
  def defaultFromParam(fakePackage: DeploymentPackage, param: Param[_]): Option[String] = {
    val fakeTarget = DeployTarget(DeployParameters(Deployer("<deployerName>"), Build("<projectName>", "<buildNo>"), Stage("<stage>"), RecipeName("<recipe>"), stacks = Seq(NamedStack("<stack>"))), NamedStack("<stack>"), Region("<region>"))
    (param.defaultValue, param.defaultValueFromContext.map(_(fakePackage, fakeTarget))) match {
      case (Some(default), _) => Some(default.toString)
      case (None, Some(Right(pkgFunction))) => Some(pkgFunction.toString)
      case (None, Some(Left(error))) => Some(s"ERROR generating default: $error")
      case (_, _) => None
    }
  }

  def generateParamDocs(deploymentTypes: Seq[DeploymentType]): Seq[(DeploymentType, Seq[(String, String, Option[String], Option[String])])] = {
    deploymentTypes.sortBy(_.name).map { dt =>
      val legacyPackage = DeploymentPackage("<packageName>",Seq(App("<app>")),Map.empty,dt.name,
        S3Path("<bucket>", "<prefix>"), legacyConfig = true, deploymentTypes)
      val newPackage = DeploymentPackage("<packageName>",Seq(App("<app>")),Map.empty,dt.name,
        S3Path("<bucket>", "<prefix>"), legacyConfig = false, deploymentTypes)

      val paramDocs = dt.params.sortBy(_.name).map { param =>
        val defaultLegacy = defaultFromParam(legacyPackage, param)
        val defaultNew = defaultFromParam(newPackage, param)
        (param.name, param.documentation, defaultLegacy, defaultNew)
      }.sortBy(_._3.isDefined)
      dt -> paramDocs
    }
  }
}
