package magenta.input.resolver

import cats.data.Validated.{Invalid, Valid}
import cats.data.{Validated, NonEmptyList => NEL}
import cats.instances.all._
import cats.syntax.traverse._
import cats.syntax.semigroup._
import magenta.artifact.S3Artifact
import magenta.deployment_type.DeploymentType
import magenta.graph.{DeploymentTasks, Graph}
import magenta.input._
import magenta.{DeployParameters, DeploymentResources}

object Resolver {
  def resolve(
      yamlConfig: String,
      deploymentResources: DeploymentResources,
      parameters: DeployParameters,
      deploymentTypes: Seq[DeploymentType],
      artifact: S3Artifact
  ): Validated[ConfigErrors, Graph[DeploymentTasks]] = {

    for {
      deploymentGraph <- resolveDeploymentGraph(
        yamlConfig,
        deploymentTypes,
        parameters.selector
      )
      taskGraph <- buildTaskGraph(
        deploymentResources,
        parameters,
        deploymentTypes,
        artifact,
        deploymentGraph
      )
    } yield taskGraph
  }

  def resolveDeploymentGraph(
      yamlConfig: String,
      deploymentTypes: Seq[DeploymentType],
      selector: DeploymentSelector
  ): Validated[ConfigErrors, Graph[Deployment]] = {

    for {
      config <- RiffRaffYamlReader.fromString(yamlConfig)
      deployments <- DeploymentResolver.resolve(config)
      validatedDeployments <- deployments.traverse { deployment =>
        DeploymentTypeResolver.validateDeploymentType(
          deployment,
          deploymentTypes
        )
      }
      prunedDeployments = DeploymentPruner.prune(
        validatedDeployments,
        DeploymentPruner.create(selector)
      )
      graph = buildParallelisedGraph(prunedDeployments)
    } yield graph
  }

  private[resolver] def buildTaskGraph(
      deploymentResources: DeploymentResources,
      parameters: DeployParameters,
      deploymentTypes: Seq[DeploymentType],
      artifact: S3Artifact,
      graph: Graph[Deployment]
  ): Validated[ConfigErrors, Graph[DeploymentTasks]] = {

    val flattenedGraph = DeploymentGraphActionFlattening.flattenActions(graph)
    val deploymentTaskGraph = flattenedGraph.map { deployment =>
      TaskResolver.resolve(
        deployment,
        deploymentResources,
        parameters,
        deploymentTypes,
        artifact
      )
    }

    if (deploymentTaskGraph.nodes.values.exists(_.isInvalid)) {
      Invalid(
        deploymentTaskGraph.toList
          .collect { case Invalid(errors) => errors }
          .reduce(_ |+| _)
      )
    } else {
      // we know they are all good
      Valid(deploymentTaskGraph.map(_.toOption.get))
    }
  }

  private[resolver] def buildParallelisedGraph(
      userSelectedDeployments: List[Deployment]
  ) = {
    val stacks = userSelectedDeployments.flatMap(_.stacks.toList).distinct
    val regions = userSelectedDeployments.flatMap(_.regions.toList).distinct

    val stackRegionGraphs: List[Graph[Deployment]] = for {
      stack <- stacks
      region <- regions
      deploymentsForStackAndRegion = DeploymentPruner.prune(
        userSelectedDeployments,
        DeploymentPruner.StackAndRegion(stack, region)
      )
      graphForStackAndRegion = DeploymentGraphBuilder.buildGraph(
        deploymentsForStackAndRegion
      )
    } yield graphForStackAndRegion

    stackRegionGraphs.reduceLeft(_ joinParallel _)
  }

}
