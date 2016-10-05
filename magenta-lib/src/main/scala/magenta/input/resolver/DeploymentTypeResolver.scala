package magenta.input.resolver

import magenta.deployment_type.DeploymentType
import magenta.input.{ConfigError, Deployment}

object DeploymentTypeResolver {
  def validateDeploymentType(deployment: Deployment,
                             availableTypes: Seq[DeploymentType]): Either[ConfigError, Deployment] = {
    for {
      deploymentType <- availableTypes
        .find(_.name == deployment.`type`)
        .toRight(ConfigError(deployment.name, s"Unknown type ${deployment.`type`}"))
        .right
      deployment <- resolveDeploymentActions(deployment, deploymentType).right
    } yield deployment
  }

  private[input] def resolveDeploymentActions(deployment: Deployment,
                                              deploymentType: DeploymentType): Either[ConfigError, Deployment] = {
    val actions = deployment.actions.getOrElse(deploymentType.defaultActions)
    val invalidActions = actions.filterNot(deploymentType.actions.isDefinedAt)

    if (actions.isEmpty)
      Left(ConfigError(deployment.name, s"Either specify at least one action or omit the actions parameter"))
    else if (invalidActions.nonEmpty)
      Left(
        ConfigError(deployment.name, s"Invalid action ${invalidActions.mkString(", ")} for type ${deployment.`type`}"))
    else
      Right(deployment.copy(actions = Some(actions)))
  }
}
