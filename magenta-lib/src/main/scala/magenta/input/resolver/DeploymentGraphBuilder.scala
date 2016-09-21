package magenta.input.resolver

import magenta.graph.{Edge, EndNode, Graph, MidNode, Node, StartNode}
import magenta.input.Deployment

object DeploymentGraphBuilder {
  def buildGraph(deployments: List[Deployment]): Graph[Deployment] = {
    val edges = startEdges(deployments) ::: midEdges(deployments) ::: endEdges(deployments)
    Graph(edges.toSet)
  }

  implicit class RichDeploymentList(deployments: List[Deployment]) {
    def names: Set[String] = deployments.map(_.name).toSet
    def dependencies: Set[String] = deployments.flatMap(_.dependencies).toSet
  }

  private[resolver] def startEdges(deployments: List[Deployment]): List[Edge[Deployment]] = {
    val targetNodes = for {
      deployment <- deployments
      if !deployment.dependencies.exists(deployments.names.contains)
    } yield MidNode(deployment)
    edges(StartNode, targetNodes)
  }

  private[resolver] def midEdges(deployments: List[Deployment]): List[Edge[Deployment]] = {
    deployments.flatMap { dependency =>
      val targetNodes = for {
        dependent <- deployments.filter(_.dependencies.contains(dependency.name))
      } yield MidNode(dependent)
      edges(MidNode(dependency), targetNodes)
    }
  }

  private[resolver] def endEdges(deployments: List[Deployment]): List[Edge[Deployment]] = {
    for {
      deployment <- deployments
      if deployments.forall(!_.dependencies.contains(deployment.name))
    } yield {
      MidNode(deployment) ~> EndNode
    }
  }

  private[resolver] def edges(from: Node[Deployment], to: List[Node[Deployment]]): List[Edge[Deployment]] = {
    to.map(from ~>).reprioritise
  }
}