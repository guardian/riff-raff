package docs

import magenta.Strategy.MostlyHarmless
import magenta.artifact.S3Path
import magenta.{
  App,
  Build,
  DeployParameters,
  DeployTarget,
  Deployer,
  DeploymentPackage,
  Region,
  Stack,
  Stage
}
import magenta.deployment_type.{DeploymentType, Param}

case class ActionDoc(name: String, documentation: String, isDefault: Boolean)
case class ParamDoc(
    name: String,
    documentation: String,
    default: Option[String]
)
case class DeployTypeDocs(
    documentation: String,
    actions: Seq[ActionDoc],
    params: Seq[ParamDoc]
)

object DeployTypeDocs {
  def defaultFromParam(
      fakePackage: DeploymentPackage,
      param: Param[_]
  ): Option[String] = {
    val fakeTarget = DeployTarget(
      DeployParameters(
        Deployer("<deployerName>"),
        Build("<projectName>", "<buildNo>"),
        Stage("<stage>"),
        updateStrategy = MostlyHarmless
      ),
      Stack("<stack>"),
      Region("<region>")
    )
    (
      param.defaultValue,
      param.defaultValueFromContext.map(_(fakePackage, fakeTarget))
    ) match {
      case (Some(default), _)               => Some(default.toString)
      case (None, Some(Right(pkgFunction))) => Some(pkgFunction.toString)
      case (None, Some(Left(error)))        =>
        Some(s"ERROR generating default: $error")
      case (_, _) => None
    }
  }

  def generateDocs(
      deploymentTypes: Seq[DeploymentType]
  ): Seq[(DeploymentType, DeployTypeDocs)] = {
    deploymentTypes.sortBy(_.name).map { dt =>
      val actionDocs = dt.actionsMap.values
        .map { action =>
          ActionDoc(
            action.name,
            action.documentation,
            dt.defaultActionNames.contains(action.name)
          )
        }
        .toList
        .sortBy { doc =>
          val defaultOrdering = dt.defaultActionNames.indexOf(doc.name)
          val defaultOrderingWithNonDefaultLast =
            if (defaultOrdering == -1) Int.MaxValue else defaultOrdering
          (defaultOrderingWithNonDefaultLast, doc.name)
        }

      val deploymentPackage = DeploymentPackage(
        "<packageName>",
        App("<app>"),
        Map.empty,
        dt.name,
        S3Path("<bucket>", "<prefix>"),
        deploymentTypes
      )

      val paramDocs = dt.params
        .sortBy(_.name)
        .map { param =>
          val defaults = defaultFromParam(deploymentPackage, param)
          ParamDoc(param.name, param.documentation, defaults)
        }
        .sortBy(doc => (doc.default.isDefined, doc.name))

      dt -> DeployTypeDocs(dt.documentation, actionDocs, paramDocs)
    }
  }
}
