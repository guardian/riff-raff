package docs

import magenta.artifact.S3Path
import magenta.{App, Build, DeployParameters, DeployTarget, Deployer, DeploymentPackage, NamedStack, RecipeName, Region, Stage}
import magenta.deployment_type.{DeploymentType, Param}

case class ActionDoc(name: String, documentation: String, isDefault: Boolean)
case class ParamDoc(name: String, documentation: String, defaultLegacy: Option[String], default: Option[String])
case class DeployTypeDocs(documentation: String, actions: Seq[ActionDoc], params: Seq[ParamDoc])

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

  def generateDocs(deploymentTypes: Seq[DeploymentType]): Seq[(DeploymentType, DeployTypeDocs)] = {
    deploymentTypes.sortBy(_.name).map { dt =>
      val actionDocs = dt.actionsMap.values.map { action =>
        ActionDoc(action.name, action.documentation, dt.defaultActionNames.contains(action.name))
      }.toList.sortBy{
        doc =>
          val defaultOrdering = dt.defaultActionNames.indexOf(doc.name)
          val defaultOrderingWithNonDefaultLast = if (defaultOrdering == -1) Int.MaxValue else defaultOrdering
          (defaultOrderingWithNonDefaultLast, doc.name)
      }

      val legacyPackage = DeploymentPackage("<packageName>",Seq(App("<app>")),Map.empty,dt.name,
        S3Path("<bucket>", "<prefix>"), legacyConfig = true, deploymentTypes)
      val newPackage = DeploymentPackage("<packageName>",Seq(App("<app>")),Map.empty,dt.name,
        S3Path("<bucket>", "<prefix>"), legacyConfig = false, deploymentTypes)

      val paramDocs = dt.params.sortBy(_.name).map { param =>
        val defaultLegacy = defaultFromParam(legacyPackage, param)
        val defaultNew = defaultFromParam(newPackage, param)
        ParamDoc(param.name, param.documentation, defaultLegacy, defaultNew)
      }.sortBy(doc => (doc.default.isDefined, doc.name))

      dt -> DeployTypeDocs(dt.documentation, actionDocs, paramDocs)
    }
  }
}
