package magenta.input.resolver

import magenta.deployment_type.DeploymentType
import magenta.input.{ConfigError, Deployment}

object DeploymentTypeResolver {

  def validateDeploymentType(deployment: Deployment, availableTypes: Seq[DeploymentType]): Either[ConfigError, Deployment] = {
    for {
      deploymentType <- availableTypes.find(_.name == deployment.`type`)
        .toRight(ConfigError(deployment.name, s"Unknown type ${deployment.`type`}")).right
      deploymentWithActions <- resolveDeploymentActions(deployment, deploymentType).right
      deployment <- verifyDeploymentParameters(deploymentWithActions, deploymentType).right
    } yield deployment
  }

  private[input] def resolveDeploymentActions(deployment: Deployment, deploymentType: DeploymentType): Either[ConfigError, Deployment] = {
    val actions = deployment.actions.getOrElse(deploymentType.defaultActions)
    val invalidActions = actions.filterNot(deploymentType.actions.isDefinedAt)

    if (actions.isEmpty)
      Left(ConfigError(deployment.name, s"Either specify at least one action or omit the actions parameter"))
    else if (invalidActions.nonEmpty)
      Left(ConfigError(deployment.name, s"Invalid action ${invalidActions.mkString(", ")} for type ${deployment.`type`}"))
    else
      Right(deployment.copy(actions=Some(actions)))
  }

  private[input] def verifyDeploymentParameters(deployment: Deployment, deploymentType: DeploymentType): Either[ConfigError, Deployment] = {
    val validParameterNames = deploymentType.params.map(_.name)
    val requiredParameterNames =
      deploymentType.params.filterNot(dt => dt.defaultValue.isDefined || dt.defaultValueFromPackage.isDefined).map(_.name).toSet

    val actualParamNames = deployment.parameters.keySet
    val missingParameters = requiredParameterNames -- actualParamNames
    val unusedParameters = actualParamNames -- validParameterNames
    if (missingParameters.nonEmpty)
      Left(ConfigError(deployment.name, s"Parameters required for ${deploymentType.name} deployments not provided: ${missingParameters.mkString(", ")}"))
    else if (unusedParameters.nonEmpty)
      Left(ConfigError(deployment.name, s"Parameters provided but not used by ${deploymentType.name} deployments: ${unusedParameters.mkString(", ")}"))
    else
      Right(deployment)
  }
}
