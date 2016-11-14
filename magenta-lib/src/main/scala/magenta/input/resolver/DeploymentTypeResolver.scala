package magenta.input.resolver

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import magenta.deployment_type.DeploymentType
import magenta.input.{ConfigErrors, Deployment}

object DeploymentTypeResolver {

  def validateDeploymentType(deployment: Deployment, availableTypes: Seq[DeploymentType]): Validated[ConfigErrors, Deployment] = {
    for {
      deploymentType <- Validated.fromOption(availableTypes.find(_.name == deployment.`type`),
        ConfigErrors(deployment.name, s"Unknown type ${deployment.`type`}"))
      deploymentWithActions <- resolveDeploymentActions(deployment, deploymentType)
      deployment <- verifyDeploymentParameters(deploymentWithActions, deploymentType)
    } yield deployment
  }

  private[input] def resolveDeploymentActions(deployment: Deployment, deploymentType: DeploymentType): Validated[ConfigErrors, Deployment] = {
    val actions = deployment.actions.getOrElse(deploymentType.defaultActionNames)
    val invalidActions = actions.filterNot(deploymentType.actionsMap.isDefinedAt)

    if (actions.isEmpty)
      Invalid(ConfigErrors(deployment.name, s"Either specify at least one action or omit the actions parameter"))
    else if (invalidActions.nonEmpty)
      Invalid(ConfigErrors(deployment.name, s"Invalid action ${invalidActions.mkString(", ")} for type ${deployment.`type`}"))
    else
      Valid(deployment.copy(actions=Some(actions)))
  }

  private[input] def verifyDeploymentParameters(deployment: Deployment, deploymentType: DeploymentType): Validated[ConfigErrors, Deployment] = {
    val validParameterNames = deploymentType.params.map(_.name)
    val requiredParameterNames =
      deploymentType.params.filter(_.requiredInYaml).map(_.name).toSet

    val actualParamNames = deployment.parameters.keySet
    val missingParameters = requiredParameterNames -- actualParamNames
    val unusedParameters = actualParamNames -- validParameterNames
    if (missingParameters.nonEmpty)
      Invalid(ConfigErrors(deployment.name, s"Parameters required for ${deploymentType.name} deployments not provided: ${missingParameters.mkString(", ")}"))
    else if (unusedParameters.nonEmpty)
      Invalid(ConfigErrors(deployment.name, s"Parameters provided but not used by ${deploymentType.name} deployments: ${unusedParameters.mkString(", ")}"))
    else
      Valid(deployment)
  }
}
