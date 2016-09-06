package magenta.graph

sealed trait Node[+T] {
  def ~>[R >: T](to: Node[R]): Edge[R] = Edge(this, to)
  def maybeValue: Option[T] = None
}
case object StartNode extends Node[Nothing]
case class MidNode[T](value: T) extends Node[T] {
  override def maybeValue: Option[T] = Some(value)
}
case object EndNode extends Node[Nothing]

case class Edge[+T](from: Node[T], to: Node[T], priority: Int = 1) {
  def incPriority(n: Int) = this.copy(priority = priority + n)
  def ~=[R >: T](other: Edge[R]) = from == other.from && to == other.to
}

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

  val isEmpty = dataNodes.isEmpty

  def get(node: T): MidNode[T] = dataNodes.find(_.value == node).get
  def successors(node: Node[T]): Set[Node[T]] = edges.filter(_.from == node).map(_.to)
  def orderedSuccessors(node: Node[T]): List[Node[T]] = edges.filter(_.from == node).toList.sortBy(_.priority).map(_.to)
  def predecessors(node: Node[T]): Set[Node[T]] = edges.filter(_.to == node).map(_.from)

  def replace(node: Node[T], withNode: Node[T]): Graph[T] = {
    val newEdges = edges.map {
      case Edge(from, to, priority) if from == node => Edge(withNode, to, priority)
      case Edge(from, to, priority) if to == node => Edge(from, withNode, priority)
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
    if (other.isEmpty) this
    else if (isEmpty) other
    else {
      val edgesToMerge = other.edges.filterNot(e1 => edges.exists(e1 ~= _))
      val otherEdges = edgesToMerge.map { edge =>
        val maxPriority = edges.filter(_.from == edge.from).map(_.priority).reduceOption(Math.max).getOrElse(0)
        edge.copy(priority = edge.priority + maxPriority)
      }
      Graph(edges ++ otherEdges)
    }
  }

  def joinSeries(other: Graph[T]): Graph[T] = {
    val ourEndEdges = edges.filter(_.to == EndNode)
    val otherStartEdges = other.edges.filter(_.from == StartNode)
    val joiningEdges = ourEndEdges.flatMap { endEdge =>
      otherStartEdges.map(startEdge => Edge(endEdge.from, startEdge.to, startEdge.priority))
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
        val successors = this.orderedSuccessors(node)
        successors.foldLeft(List(node)){ case (acc, successor) =>
          acc ::: traverseFrom(successor, visited ++ acc)
        }
      }
    }
    traverseFrom(StartNode, Set.empty).flatMap(_.maybeValue)
  }
}
