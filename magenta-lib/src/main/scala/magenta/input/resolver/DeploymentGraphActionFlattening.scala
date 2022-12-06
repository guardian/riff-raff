package magenta.input.resolver

import cats.data.NonEmptyList
import magenta.graph.{Graph, ValueNode}
import magenta.input.Deployment

object DeploymentGraphActionFlattening {
  def flattenActions(deploymentGraph: Graph[Deployment]): Graph[Deployment] = {
    deploymentGraph.flatMap {
      case ValueNode(deployment) =>
        val deploymentPerAction = for {
          action <- deployment.actions
        } yield deployment.copy(actions = NonEmptyList.of(action))
        Graph.from(deploymentPerAction.toList)
      case _ => Graph.empty
    }
  }
}
