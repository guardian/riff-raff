package magenta.input.resolver

import magenta.artifact.S3Artifact
import magenta.{DeployParameters, DeploymentResources}
import magenta.deployment_type.DeploymentType
import magenta.graph.{DeploymentTasks, Graph}
import magenta.input._

object Resolver {
  def resolve(yamlConfig: String, deploymentResources: DeploymentResources, parameters: DeployParameters,
    deploymentTypes: Seq[DeploymentType], artifact: S3Artifact): Either[ConfigErrors, Graph[DeploymentTasks]] = {

    val config = RiffRaffYamlReader.fromString(yamlConfig)
    val deployments = DeploymentResolver.resolve(config)

    val validatedDeployments = deployments.map { either =>
      either.right.flatMap { deployment =>
        DeploymentTypeResolver.validateDeploymentType(deployment, DeploymentType.all)
      }
    }

    if (validatedDeployments.exists(_.isLeft)) {
      Left(ConfigErrors(validatedDeployments.flatMap(_.left.toOption)))
    } else {
      val deployments = validatedDeployments.map(_.right.get)

      // TODO: this is a placeholder for when we filter previews in the UI
      val userSelectedDeployments = deployments

      val stacks = userSelectedDeployments.flatMap(_.stacks).distinct
      val regions = userSelectedDeployments.flatMap(_.regions).distinct

      val stackRegionGraphs: List[Graph[Deployment]] = for {
        stack <- stacks
        region <- regions
        deploymentsForStackAndRegion = filterDeployments(userSelectedDeployments, stack, region)
        graphForStackAndRegion = DeploymentGraphBuilder.buildGraph(deploymentsForStackAndRegion)
      } yield graphForStackAndRegion

      val combinedGraph = stackRegionGraphs.reduceLeft(_ joinParallel _)
      val flattenedGraph = DeploymentGraphActionFlattening.flattenActions(combinedGraph)
      val deploymentTaskGraph = flattenedGraph.map { deployment =>
        TaskResolver.resolve(deployment, deploymentResources, parameters, deploymentTypes, artifact)
      }

      if (deploymentTaskGraph.nodes.values.exists(_.isLeft)) {
        Left(ConfigErrors(deploymentTaskGraph.nodes.toList.values.flatMap(_.left.toOption)))
      } else {
        Right(deploymentTaskGraph.map(_.right.get))
      }
    }
  }

  private[resolver] def filterDeployments(deployments: List[Deployment], stack: String, region: String): List[Deployment] = {
    deployments.flatMap { deployment =>
      if (deployment.stacks.contains(stack) && deployment.regions.contains(region))
        Some(deployment.copy(stacks = List(stack), regions = List(region)))
      else None
    }
  }
}
