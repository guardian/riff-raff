package magenta.input.resolver

import magenta.graph.{Graph, ValueNode}
import magenta.input.Deployment

object DeploymentGraphActionFlattening {
  def flattenActions(deploymentGraph: Graph[Deployment]): Graph[Deployment] = {
    deploymentGraph.flatMap {
      case ValueNode(deployment) =>
        val deploymentPerAction = for {
          actionsList <- deployment.actions.toList
          action <- actionsList
        } yield deployment.copy(actions = Some(List(action)))
        Graph.from(deploymentPerAction)
      case _ => Graph.empty
    }
  }
}
