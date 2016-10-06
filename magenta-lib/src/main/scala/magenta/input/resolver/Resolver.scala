package magenta.input.resolver

import cats.data.Validated.{Invalid, Valid}
import cats.data.{ValidatedNel, NonEmptyList => NEL}
import cats.instances.all._
import magenta.artifact.S3Artifact
import magenta.deployment_type.DeploymentType
import magenta.graph.{DeploymentTasks, Graph}
import magenta.input._
import magenta.{DeployParameters, DeploymentResources}

object Resolver {
  def resolve(yamlConfig: String, deploymentResources: DeploymentResources, parameters: DeployParameters,
    deploymentTypes: Seq[DeploymentType], artifact: S3Artifact): ValidatedNel[ConfigError, Graph[DeploymentTasks]] = {

    resolveDeploymentGraph(yamlConfig).andThen { graph =>
      val flattenedGraph = DeploymentGraphActionFlattening.flattenActions(graph)
      val deploymentTaskGraph = flattenedGraph.map { deployment =>
        TaskResolver.resolve(deployment, deploymentResources, parameters, deploymentTypes, artifact)
      }

      if (deploymentTaskGraph.nodes.values.exists(_.isInvalid)) {
        Invalid(deploymentTaskGraph.toList.collect { case Invalid(errors) => errors }.reduce(_ concat _))
      } else {
        // we know they are all good
        Valid(deploymentTaskGraph.map(_.toOption.get))
      }
    }
  }

  def resolveDeploymentGraph(yamlConfig: String): ValidatedNel[ConfigError, Graph[Deployment]] = {
    val validatedDeployments = RiffRaffYamlReader.fromString(yamlConfig).andThen { config =>
      DeploymentResolver.resolve(config)
    }.andThen { deployments =>
      deployments.map { deployment =>
        DeploymentTypeResolver.validateDeploymentType(deployment, DeploymentType.all).map(List(_))
      }.reduceLeft(_ combine _)
    }

    validatedDeployments.map { deployments =>
      // TODO: this is a placeholder for when we filter previews in the UI
      val userSelectedDeployments = deployments

      val stacks = userSelectedDeployments.flatMap(_.stacks.toList).distinct
      val regions = userSelectedDeployments.flatMap(_.regions.toList).distinct

      val stackRegionGraphs: List[Graph[Deployment]] = for {
        stack <- stacks
        region <- regions
        deploymentsForStackAndRegion = filterDeployments(userSelectedDeployments, stack, region)
        graphForStackAndRegion = DeploymentGraphBuilder.buildGraph(deploymentsForStackAndRegion)
      } yield graphForStackAndRegion

      stackRegionGraphs.reduceLeft(_ joinParallel _)
    }
  }

  private[resolver] def filterDeployments(deployments: List[Deployment], stack: String, region: String): List[Deployment] = {
    deployments.flatMap { deployment =>
      if (deployment.stacks.exists(stack ==) && deployment.regions.exists(region ==))
        Some(deployment.copy(stacks = NEL.of(stack), regions = NEL.of(region)))
      else None
    }
  }
}
