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
  def resolve(yamlConfig: String, deploymentResources: DeploymentResources, parameters: DeployParameters,
    deploymentTypes: Seq[DeploymentType], artifact: S3Artifact): Validated[ConfigErrors, Graph[DeploymentTasks]] = {

    for {
      deploymentGraph <- resolveDeploymentGraph(yamlConfig, deploymentTypes, parameters.filter)
      taskGraph <- buildTaskGraph(deploymentResources, parameters, deploymentTypes, artifact, deploymentGraph)
    } yield taskGraph
  }

  def resolveDeploymentGraph(yamlConfig: String, deploymentTypes: Seq[DeploymentType],
    filter: UserDeploymentFilter): Validated[ConfigErrors, Graph[Deployment]] = {

    for {
      config <- RiffRaffYamlReader.fromString(yamlConfig)
      deployments <- DeploymentResolver.resolve(config)
      validatedDeployments <- deployments.traverseU[Validated[ConfigErrors, Deployment]]{deployment =>
        DeploymentTypeResolver.validateDeploymentType(deployment, deploymentTypes)
      }
      userFilteredDeployments = filterDeployments(validatedDeployments, createFilter(filter))
      graph = buildParallelisedGraph(userFilteredDeployments)
    } yield graph
  }

  private def buildTaskGraph(deploymentResources: DeploymentResources, parameters: DeployParameters,
    deploymentTypes: Seq[DeploymentType], artifact: S3Artifact, graph: Graph[Deployment]): Validated[ConfigErrors, Graph[DeploymentTasks]] = {

    val flattenedGraph = DeploymentGraphActionFlattening.flattenActions(graph)
    val deploymentTaskGraph = flattenedGraph.map { deployment =>
      TaskResolver.resolve(deployment, deploymentResources, parameters, deploymentTypes, artifact)
    }

    if (deploymentTaskGraph.nodes.values.exists(_.isInvalid)) {
      Invalid(deploymentTaskGraph.toList.collect { case Invalid(errors) => errors }.reduce(_ |+| _))
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
      deploymentsForStackAndRegion = filterDeployments(userSelectedDeployments, FilterStackAndRegion(stack, region))
      graphForStackAndRegion = DeploymentGraphBuilder.buildGraph(deploymentsForStackAndRegion)
    } yield graphForStackAndRegion

    stackRegionGraphs.reduceLeft(_ joinParallel _)
  }

  private[resolver] def filterDeployments(deployments: List[Deployment], filter: DeploymentFilter): List[Deployment] = {
    deployments.flatMap(filter(_))
  }

  private[resolver] def createFilter(userFilter: UserDeploymentFilter): DeploymentFilter =
    userFilter match {
      case NoFilter => Identity
      case DeploymentIdsFilter(ids) => FilterIds(ids)
    }

  type DeploymentFilter = Deployment => Option[Deployment]

  /** A filter function that returns all deploymens unmodified */
  val Identity: DeploymentFilter = deployment => Some(deployment)

  /** Returns a function that modifies deployments to apply to only the given stack and region.
    * If the stack and region do not appear in the deployment then None is returned.
    * If the stack and region do appear then a deployment will be returned with only that region and stack.
    */
  def FilterStackAndRegion(stack: String, region: String): DeploymentFilter = { deployment =>
    if (deployment.stacks.exists(stack ==) && deployment.regions.exists(region ==))
      Some(deployment.copy(stacks = NEL.of(stack), regions = NEL.of(region)))
    else
      None
  }
  /** This filters a deployment against the list of selected IDs.
    * If none of the Ids match the deployment then None will be returned.
    * If there are IDs that match all the actions, regions and stacks then the deployment will be returned unmodified.
    * If the deployment partially matches then a modified deployment will be returned with that subset of actions,
    * regions and stacks.
    * */
  def FilterIds(ids: List[DeploymentId]): DeploymentFilter = { deployment =>
    def matchId(id: DeploymentId): Boolean = {
      deployment.name == id.name && deployment.actions.toList.flatten.contains(id.action) &&
        deployment.regions.toList.contains(id.region) && deployment.stacks.toList.contains(id.stack)
    }
    val matchingIds = ids filter matchId
    if (matchingIds.nonEmpty) {
      Some(deployment.copy(
        actions = deployment.actions.map(_.filter(a => matchingIds.exists(_.action == a))),
        regions = NEL.fromListUnsafe(deployment.regions.filter(r => matchingIds.exists(_.region == r))),
        stacks = NEL.fromListUnsafe(deployment.stacks.filter(s => matchingIds.exists(_.stack == s)))
      ))
    } else None
  }

}
