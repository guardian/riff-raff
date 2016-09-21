package magenta.input.resolver

import magenta.graph.Graph
import magenta.input.{Deployment, RiffRaffDeployConfig}

object Resolver {
  def resolve(config: RiffRaffDeployConfig) = {
    // this is a place holder that tries to tie some things together

    // turn the config into a set of deployments (resolve templates etc)
    // val deployments = DeploymentResolver.resolve(config)

    // validate that deployment types and dependencies all exist
    // val validatedDeployments = DeploymentTypeResolver.validateDeploymentType(deployment, DeploymentType.all)

    // this is a placeholder for how we filter previews in the UI
    // val filteredDeployment = ???

    // now convert it into a graph
//    val componentGraphs: List[Graph[Deployment]] = for {
//      stack <- stacks
//      region <- regions
//      deploymentsForStackAndRegion = filterDeployments(stack, region, ...)
//      graphForStackAndRegion = DeploymentGraphBuilder.buildGraph()
//    } yield graphForStackAndRegion
//    val graph = componentGraphs.reduceLeft(_ joinParallel _)

    // now flatten out the actions
    //val flattenedGraph = DeploymentGraphActionFlattening.flattenActions(graph)

    // now resolve each deployment into tasks
    //val deploymentTaskGraph = DeploymentTaskResolver.resolveGraph(flattenedGraph)
  }
}
