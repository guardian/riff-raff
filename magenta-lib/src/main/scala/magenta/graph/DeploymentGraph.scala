package magenta.graph

import magenta.tasks.Task

sealed trait Node {
  def ~>(to: Node) = Edge(this, to)
  def maybePriority: Option[Int] = None
}
case object StartNode extends Node
case class DeploymentNode(tasks: List[Task], pathName: String, priority: Int = 1) extends Node {
  override def maybePriority: Option[Int] = Some(priority)
  def incPriority(n: Int): DeploymentNode = this.copy(priority = priority + n)
}
case object EndNode extends Node

case class Edge(from: Node, to: Node)

/**
  * This graph is specialised for deployment. It consists of a start node, an end node and any number of
  * deployment nodes. The graph structure represents which deployments must be run in series and which can be run in
  * parallel.
  */
object DeploymentGraph {
  def apply(tasks: List[Task], pathName: String, priority: Int = 1): DeploymentGraph = {
    val deploymentNode = DeploymentNode(tasks, pathName, priority)
    DeploymentGraph(StartNode ~> deploymentNode, deploymentNode ~> EndNode)
  }

  def apply(edges: Edge*): DeploymentGraph = DeploymentGraph(edges.toSet)

  val empty = DeploymentGraph(StartNode ~> EndNode)
}

case class DeploymentGraph(edges: Set[Edge]) {
  val nodes = edges.map(_.from) ++ edges.map(_.to)

  val isValid: Either[List[String], Boolean] = {
    val errors =
      ((if (!nodes.contains(StartNode)) Some("No start node") else None) ::
        (if (!nodes.contains(EndNode)) Some("No end node") else None) :: Nil).flatten
    if (errors.isEmpty) Right(true) else Left(errors)
  }
  assert(isValid.isRight, s"Graph isn't valid: ${isValid.left.get.mkString(", ")}")

  def successors(node: Node): Set[Node] = edges.filter(_.from == node).map(_.to)
  def predecessors(node: Node): Set[Node] = edges.filter(_.to == node).map(_.from)
  def successorDeploymentNodes(node: Node): List[DeploymentNode] = {
    successors(node).filterDeploymentNodes.toList.sortBy(_.priority)
  }

  def replace(node: Node, withNode: Node): DeploymentGraph = {
    assert(nodes.contains(node), "Node to replace not found in graph")
    val newEdges = edges.map {
      case Edge(from, to) if from == node => Edge(withNode, to)
      case Edge(from, to) if to == node => Edge(from, withNode)
      case other => other
    }
    DeploymentGraph(newEdges)
  }

  def joinParallel(other: DeploymentGraph): DeploymentGraph = {
    val maxPriority = successorDeploymentNodes(StartNode).map(_.priority).max
    val otherEdges = other.successorDeploymentNodes(StartNode).foldLeft(other){ case(g, node) =>
      g.replace(node, node.incPriority(maxPriority))
    }.edges
    DeploymentGraph(edges ++ otherEdges)
  }

  def toList: List[Node] = {
    def traverseFrom(node: Node, visited: Set[Node]): List[Node] = {
      val predecessors: Set[Node] = this.predecessors(node)
      if ((predecessors -- visited).nonEmpty) {
        // if there are some predecessors of this node that we haven't yet visited then return empty list - we'll be back
        Nil
      } else {
        // if we've visited all the predecessors then follow all the successors
        val successors = this.successors(node).toList.sortBy{ node =>
          // order by the priority of the node
          node.maybePriority
        }
        successors.foldLeft(List(node)){ case (acc, successor) =>
          acc ::: traverseFrom(successor, visited ++ acc)
        }
      }
    }
    traverseFrom(StartNode, Set.empty)
  }

  def toTaskList = toList.filterDeploymentNodes.flatMap(_.tasks)
}
