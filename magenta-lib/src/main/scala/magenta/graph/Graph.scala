package magenta.graph

sealed trait Node[+T] {
  def ~>[R >: T](to: Node[R]): Edge[R] = Edge(this, to)
  def maybePriority: Option[Int] = None
  def maybeValue: Option[T] = None
}
case object StartNode extends Node[Nothing]
case class MidNode[T](value: T, priority: Int = 1) extends Node[T] {
  override def maybePriority: Option[Int] = Some(priority)
  def incPriority(n: Int): MidNode[T] = this.copy(priority = priority + n)
  override def maybeValue: Option[T] = Some(value)
}
case object EndNode extends Node[Nothing]

case class Edge[+T](from: Node[T], to: Node[T])

object Graph {
  def apply[T](edges: Edge[T]*): Graph[T] = Graph(edges.toSet)

  def empty[T] = Graph[T](StartNode ~> EndNode)
}

case class Graph[T](edges: Set[Edge[T]]) {
  val nodes = edges.map(_.from) ++ edges.map(_.to)
  val dataNodes = nodes.filterMidNodes

  val isValid: Either[List[String], Boolean] = {
    val errors =
      ((if (!nodes.contains(StartNode)) Some("No start node") else None) ::
        (if (!nodes.contains(EndNode)) Some("No end node") else None) :: Nil).flatten
    if (errors.isEmpty) Right(true) else Left(errors)
  }
  assert(isValid.isRight, s"Graph isn't valid: ${isValid.left.get.mkString(", ")}")

  def get(node: T): MidNode[T] = dataNodes.find(_.value == node).get
  def successors(node: Node[T]): Set[Node[T]] = edges.filter(_.from == node).map(_.to)
  def predecessors(node: Node[T]): Set[Node[T]] = edges.filter(_.to == node).map(_.from)
  def successorNodes(node: Node[T]): List[MidNode[T]] = {
    successors(node).filterMidNodes.toList.sortBy(_.priority)
  }

  def replace(node: Node[T], withNode: Node[T]): Graph[T] = {
    assert(nodes.contains(node), "Node to replace not found in graph")
    val newEdges = edges.map {
      case Edge(from, to) if from == node => Edge(withNode, to)
      case Edge(from, to) if to == node => Edge(from, withNode)
      case other => other
    }
    Graph(newEdges)
  }

  def map[R](f: MidNode[T] => MidNode[R]): Graph[R] = {
    val newNodeMap: Map[Node[T], Node[R]] = dataNodes.map { currentNode =>
      currentNode -> f(currentNode)
    }.toMap ++ Map(StartNode -> StartNode, EndNode -> EndNode)
    assert(newNodeMap.size == newNodeMap.values.toSet.size, "Source nodes must be mapped onto unique target nodes")
    val newEdges = edges.map { oldEdge =>
      Edge(newNodeMap(oldEdge.from), newNodeMap(oldEdge.to))
    }
    Graph(newEdges)
  }

  def joinParallel(other: Graph[T]): Graph[T] = {
    val maxPriority = successorNodes(StartNode).map(_.priority).max
    val otherEdges = other.successorNodes(StartNode).foldLeft(other){ case(g, node) =>
      g.replace(node, node.incPriority(maxPriority))
    }.edges
    Graph(edges ++ otherEdges)
  }

  def joinSeries(other: Graph[T]): Graph[T] = {
    val ourEndEdges = edges.filter(_.to == EndNode)
    val otherStartEdges = other.edges.filter(_.from == StartNode)
    val joiningEdges = ourEndEdges.map(_.from).flatMap { endNode =>
      otherStartEdges.map(_.to).map(startNode => Edge(endNode, startNode))
    }
    val mergedEdges = (edges -- ourEndEdges) ++ (other.edges -- otherStartEdges) ++ joiningEdges
    Graph(mergedEdges)
  }

  def toList: List[T] = {
    def traverseFrom(node: Node[T], visited: Set[Node[T]]): List[Node[T]] = {
      val predecessors: Set[Node[T]] = this.predecessors(node)
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
    traverseFrom(StartNode, Set.empty).flatMap(_.maybeValue)
  }
}
