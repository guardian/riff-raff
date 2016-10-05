package magenta.input.resolver

import magenta.artifact.{S3Artifact, S3Path}
import magenta.deployment_type.DeploymentType
import magenta.graph.DeploymentTasks
import magenta.input.{ConfigError, Deployment}
import magenta.{App, DeployParameters, DeployTarget, DeploymentPackage, DeploymentResources, NamedStack, Region}

object TaskResolver {
  def resolve(deployment: Deployment, deploymentResources: DeploymentResources, parameters: DeployParameters,
    deploymentTypes: Seq[DeploymentType], artifact: S3Artifact): Either[ConfigError, DeploymentTasks] = {
    val deploymentPackage = createDeploymentPackage(deployment, artifact)
    val deploymentTypeEither = deploymentTypes.find(_.name == deployment.`type`).
      toRight(ConfigError(deployment.name, s"Deployment type ${deployment.`type`} not found"))

    deploymentTypeEither.right.map { deploymentType =>
      val tasks = for {
        region <- deployment.regions
        stack <- deployment.stacks
        actionName <- deployment.actions.toList.flatten
        action = deploymentType.mkAction(actionName)(deploymentPackage)
        target = DeployTarget(parameters, NamedStack(stack), Region(region))
        task <- action.resolve(deploymentResources, target)
      } yield task
      DeploymentTasks(tasks,
        mkLabel(deploymentPackage.name, deployment.actions.toList.flatten, deployment.regions, deployment.stacks))
    }
  }

  private[resolver] def createDeploymentPackage(deployment: Deployment, artifact: S3Artifact): DeploymentPackage = {
    DeploymentPackage(
      name = deployment.name,
      pkgApps = Seq(App(deployment.app)),
      pkgSpecificData = deployment.parameters,
      deploymentTypeName = deployment.`type`,
      s3Package = S3Path(artifact, deployment.contentDirectory),
      legacyConfig = false
    )
  }

  private def mkLabel(name: String, actions: List[String], regions: List[String], stacks: List[String]): String = {
    val bracketList = (list: List[String]) => if (list.size <= 1) list.mkString else list.mkString("{",",","}")
    s"$name [${actions.mkString(", ")}] => ${bracketList(regions)}/${bracketList(stacks)}"
  }
}
