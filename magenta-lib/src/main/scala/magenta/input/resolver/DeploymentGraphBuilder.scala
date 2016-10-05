package magenta.input.resolver

import magenta.graph.{EndNode, Graph, ValueNode, StartNode}
import magenta.input.Deployment

object DeploymentGraphBuilder {
  def buildGraph(deployments: List[Deployment]): Graph[Deployment] = {
    val deploymentNodes = deployments.map(ValueNode.apply)
    val edges = for {
      dependency <- StartNode :: deploymentNodes
      edge <- {
        val dependents = dependency match {
          case StartNode => deploymentNodes.filterNot(_.value.dependencies.exists(deployments.map(_.name).contains))
          case ValueNode(deployment) => deploymentNodes.filter(_.value.dependencies.contains(deployment.name))
          case EndNode => throw new IllegalStateException("EndNode is not valid as the source of an edge")
        }
        val toNodes = if (dependents.nonEmpty) dependents else List(EndNode)
        toNodes.map(dependency ~>).reprioritise
      }
    } yield edge
    Graph(edges.toSet)
  }
}
