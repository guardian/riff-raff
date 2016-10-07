package magenta.input.resolver

import cats.data.Validated.{Invalid, Valid}
import cats.data.{Validated, NonEmptyList => NEL}
import cats.instances.all._
import cats.syntax.traverse._
import magenta.artifact.S3Artifact
import magenta.deployment_type.DeploymentType
import magenta.graph.{DeploymentTasks, Graph}
import magenta.input._
import magenta.{DeployParameters, DeploymentResources}

object Resolver {
  def resolve(yamlConfig: String, deploymentResources: DeploymentResources, parameters: DeployParameters,
    deploymentTypes: Seq[DeploymentType], artifact: S3Artifact): Validated[ConfigErrors, Graph[DeploymentTasks]] = {

    for {
      deploymentGraph <- resolveDeploymentGraph(yamlConfig)
      taskGraph <- buildTaskGraph(deploymentResources, parameters, deploymentTypes, artifact, deploymentGraph)
    } yield taskGraph
  }

  def resolveDeploymentGraph(yamlConfig: String): Validated[ConfigErrors, Graph[Deployment]] = {
    for {
      config <- RiffRaffYamlReader.fromString(yamlConfig)
      deployments <- DeploymentResolver.resolve(config)
      validatedDeployments <- deployments.traverseU[Validated[ConfigErrors, Deployment]]{deployment =>
        DeploymentTypeResolver.validateDeploymentType(deployment, DeploymentType.all)
      }
      userSelectedDeployments = validatedDeployments
      graph = buildParallelisedGraph(userSelectedDeployments)
    } yield graph
  }

  private def buildTaskGraph(deploymentResources: DeploymentResources, parameters: DeployParameters,
    deploymentTypes: Seq[DeploymentType], artifact: S3Artifact, graph: Graph[Deployment]): Validated[ConfigErrors, Graph[DeploymentTasks]] = {

    val flattenedGraph = DeploymentGraphActionFlattening.flattenActions(graph)
    val deploymentTaskGraph = flattenedGraph.map { deployment =>
      TaskResolver.resolve(deployment, deploymentResources, parameters, deploymentTypes, artifact)
    }

    if (deploymentTaskGraph.nodes.values.exists(_.isInvalid)) {
      Invalid(ConfigErrors(deploymentTaskGraph.toList.collect { case Invalid(errors) => errors }.reduce(_ concat _)))
    } else {
      // we know they are all good
      Valid(deploymentTaskGraph.map(_.toOption.get))
    }
  }


  private def buildParallelisedGraph(userSelectedDeployments: List[Deployment]) = {
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

  private[resolver] def filterDeployments(deployments: List[Deployment], stack: String, region: String): List[Deployment] = {
    deployments.flatMap { deployment =>
      if (deployment.stacks.exists(stack ==) && deployment.regions.exists(region ==))
        Some(deployment.copy(stacks = NEL.of(stack), regions = NEL.of(region)))
      else None
    }
  }
}
