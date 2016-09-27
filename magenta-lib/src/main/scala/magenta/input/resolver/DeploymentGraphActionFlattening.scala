package magenta.input.resolver

import magenta.graph.{Graph, MidNode}
import magenta.input.Deployment

object DeploymentGraphActionFlattening {
  def flattenActions(deploymentGraph: Graph[Deployment]): Graph[Deployment] = {
    deploymentGraph.flatMap{
      case MidNode(deployment) =>
        val deploymentPerAction = for {
          actionsList <- deployment.actions.toList
          action <- actionsList
        } yield deployment.copy(actions = Some(List(action)))
        Graph.from(deploymentPerAction)
      case _ => Graph.empty
    }
  }
}
