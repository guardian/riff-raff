package magenta.input.resolver

import cats.data.{NonEmptyList, Validated}
import cats.data.Validated.{Invalid, Valid}
import magenta.deployment_type.DeploymentType
import magenta.input.{ConfigErrors, Deployment}

object DeploymentTypeResolver {

  def validateDeploymentType(
      deployment: PartiallyResolvedDeployment,
      availableTypes: Seq[DeploymentType]
  ): Validated[ConfigErrors, Deployment] = {
    for {
      deploymentType <- Validated.fromOption(
        availableTypes.find(_.name == deployment.`type`),
        ConfigErrors(deployment.name, s"Unknown type ${deployment.`type`}")
      )
      deploymentWithActions <- resolveDeploymentActions(
        deployment,
        deploymentType
      )
      deployment <- verifyDeploymentParameters(
        deploymentWithActions,
        deploymentType
      )
    } yield deployment
  }

  private[input] def resolveDeploymentActions(
      deployment: PartiallyResolvedDeployment,
      deploymentType: DeploymentType
  ): Validated[ConfigErrors, Deployment] = {
    val actions = deployment.actions.orElse(
      NonEmptyList.fromList(deploymentType.defaultActionNames)
    )
    val invalidActions = actions.flatMap(as =>
      NonEmptyList.fromList(
        as.filter(!deploymentType.actionsMap.isDefinedAt(_))
      )
    )

    invalidActions
      .map(invalids =>
        Invalid(
          ConfigErrors(
            deployment.name,
            s"Invalid action ${invalids.toList.mkString(", ")} for type ${deployment.`type`}"
          )
        )
      )
      .getOrElse {
        Validated.fromOption(
          actions.map(as =>
            Deployment(
              name = deployment.name,
              `type` = deployment.`type`,
              stacks = deployment.stacks,
              regions = deployment.regions,
              allowedStages = deployment.allowedStages,
              actions = as,
              app = deployment.app,
              contentDirectory = deployment.contentDirectory,
              dependencies = deployment.dependencies,
              parameters = deployment.parameters
            )
          ),
          ConfigErrors(
            deployment.name,
            s"Either specify at least one action or omit the actions parameter"
          )
        )
      }
  }

  private[input] def verifyDeploymentParameters(
      deployment: Deployment,
      deploymentType: DeploymentType
  ): Validated[ConfigErrors, Deployment] = {
    val validParameterNames = deploymentType.params.map(_.name)
    val requiredParameterNames =
      deploymentType.params.filter(_.required).map(_.name).toSet

    val actualParamNames = deployment.parameters.keySet
    val missingParameters = requiredParameterNames -- actualParamNames
    val unusedParameters = actualParamNames -- validParameterNames
    if (missingParameters.nonEmpty)
      Invalid(
        ConfigErrors(
          deployment.name,
          s"Parameters required for ${deploymentType.name} deployments not provided: ${missingParameters
              .mkString(", ")}"
        )
      )
    else if (unusedParameters.nonEmpty)
      Invalid(
        ConfigErrors(
          deployment.name,
          s"Parameters provided but not used by ${deploymentType.name} deployments: ${unusedParameters
              .mkString(", ")}"
        )
      )
    else
      Valid(deployment)
  }
}
