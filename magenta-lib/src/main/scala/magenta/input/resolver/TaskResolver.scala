package magenta.input.resolver

import cats.data.{Validated, NonEmptyList => NEL}
import magenta.artifact.{S3Artifact, S3Path}
import magenta.deployment_type.DeploymentType
import magenta.graph.DeploymentTasks
import magenta.input.{ConfigErrors, Deployment}
import magenta.{App, DeployParameters, DeployTarget, DeploymentPackage, DeploymentResources, NamedStack, Region}

object TaskResolver {
  def resolve(deployment: Deployment, deploymentResources: DeploymentResources, parameters: DeployParameters,
    deploymentTypes: Seq[DeploymentType], artifact: S3Artifact): Validated[ConfigErrors, DeploymentTasks] = {
    val validatedDeploymentType = Validated.fromOption(deploymentTypes.find(_.name == deployment.`type`),
      ConfigErrors(deployment.name, s"Deployment type ${deployment.`type`} not found"))

    validatedDeploymentType.map { deploymentType =>
      val deploymentPackage = createDeploymentPackage(deployment, artifact, deploymentTypes)
      val tasks = for {
        region <- deployment.regions.toList
        stack <- deployment.stacks.toList
        actionName <- deployment.actions.toList.flatten
        action = deploymentType.mkAction(actionName)(deploymentPackage)
        target = DeployTarget(parameters, NamedStack(stack), Region(region))
        task <- action.resolve(deploymentResources, target)
      } yield task
      DeploymentTasks(tasks,
        mkLabel(deploymentPackage.name, deployment.actions.toList.flatten, deployment.regions, deployment.stacks))
    }
  }

  private[resolver] def createDeploymentPackage(deployment: Deployment, artifact: S3Artifact,
    deploymentTypes: Seq[DeploymentType]): DeploymentPackage = {

    DeploymentPackage(
      name = deployment.name,
      pkgApps = Seq(App(deployment.app)),
      pkgSpecificData = deployment.parameters,
      deploymentTypeName = deployment.`type`,
      s3Package = S3Path(artifact, deployment.contentDirectory),
      legacyConfig = false,
      deploymentTypes = deploymentTypes
    )
  }

  private def mkLabel(name: String, actions: List[String], regions: NEL[String], stacks: NEL[String]): String = {
    val bracketList = (list: List[String]) => if (list.size <= 1) list.mkString else list.mkString("{",",","}")
    s"$name [${actions.mkString(", ")}] => ${bracketList(regions.toList)}/${bracketList(stacks.toList)}"
  }
}
