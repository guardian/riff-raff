package magenta.input.resolver

import magenta.graph.{EndNode, Graph, MidNode, StartNode}
import magenta.input.Deployment

object DeploymentGraphBuilder {
  def buildGraph(deployments: List[Deployment]): Graph[Deployment] = {
    val edges = allEdges(deployments)
    Graph(edges.toSet)
  }

  private[resolver] def allEdges(deployments: List[Deployment]) = {
    val deploymentNodes = deployments.map(MidNode.apply)
    for {
      from <- StartNode :: deploymentNodes
      edge <- {
        val targets = from match {
          case StartNode => deploymentNodes.filterNot(_.value.dependencies.exists(deployments.map(_.name).contains))
          case MidNode(deployment) => deploymentNodes.filter(_.value.dependencies.contains(deployment.name))
          case EndNode => throw new IllegalStateException("EndNode is not valid as the source of an edge")
        }
        val to = if (targets.nonEmpty) targets else List(EndNode)
        to.map(from ~>).reprioritise
      }
    } yield edge
  }
}