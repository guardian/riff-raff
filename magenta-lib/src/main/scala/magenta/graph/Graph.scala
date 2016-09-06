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
  val underlyingNodes = nodes.flatMap(_.maybeValue)
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

  def outgoing(node: Node[T]): Set[Edge[T]] = edges.filter(_.from == node)
  def successors(node: Node[T]): Set[Node[T]] = outgoing(node).map(_.to)
  def orderedOutgoing(node: Node[T]): List[Edge[T]] = outgoing(node).toList.sortBy(_.priority)
  def orderedSuccessors(node: Node[T]): List[Node[T]] = orderedOutgoing(node).map(_.to)

  def incoming(node: Node[T]): Set[Edge[T]] = edges.filter(_.to == node)
  def predecessors(node: Node[T]): Set[Node[T]] = incoming(node).map(_.from)

  def replace(node: Node[T], withNode: Node[T]): Graph[T] = {
    val newEdges = edges.map {
      case Edge(from, to, priority) if from == node => Edge(withNode, to, priority)
      case Edge(from, to, priority) if to == node => Edge(from, withNode, priority)
      case other => other
    }
    Graph(newEdges)
  }

  def map[R](f: T => R): Graph[R] = {
    val newNodeMap: Map[Node[T], Node[R]] = dataNodes.map { currentNode =>
      currentNode -> MidNode(f(currentNode.value))
    }.toMap ++ Map(StartNode -> StartNode, EndNode -> EndNode)
    assert(newNodeMap.size == newNodeMap.values.toSet.size, "Source nodes must be mapped onto unique target nodes")
    val newEdges = edges.map { oldEdge =>
      Edge(newNodeMap(oldEdge.from), newNodeMap(oldEdge.to), oldEdge.priority)
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
    val ourEndEdges = incoming(EndNode)
    val otherStartEdges = other.orderedOutgoing(StartNode)
    val joiningEdges = ourEndEdges.foldLeft(edges) { case (acc, endEdge) =>
      val existingOutgoing = orderedOutgoing(endEdge.from)
      val targetEdges = otherStartEdges.map(startEdge => Edge(endEdge.from, startEdge.to))
      val replacementEdges = existingOutgoing.replace(endEdge, targetEdges).reprioritise
      acc -- existingOutgoing ++ replacementEdges
    }
    val mergedEdges = joiningEdges ++ (other.edges -- otherStartEdges)
    Graph(mergedEdges)
  }

  lazy val nodeList: List[Node[T]] = {
    def traverseFrom(node: Node[T], traversed: Set[Edge[T]]): (List[Node[T]], Set[Edge[T]]) = {
      val incoming: Set[Edge[T]] = this.incoming(node)
      if ((incoming -- traversed).nonEmpty) {
        // if there are some incoming edges to this node that we haven't yet traversed then ignore this node - we'll be back
        (Nil, traversed)
      } else {
        // if we've traversed all of the incoming edges then follow all the outgoing edges
        val outgoing = this.orderedOutgoing(node)
        outgoing.foldLeft((List(node), traversed)){ case ((nodeAcc, edgeAcc), successor) =>
          val next = traverseFrom(successor.to, edgeAcc + successor)
          (nodeAcc ::: next._1, edgeAcc ++ next._2)
        }
      }
    }
    traverseFrom(StartNode, Set.empty)._1
  }

  lazy val toList: List[T] = nodeList.flatMap(_.maybeValue)

  override def toString: String = {
    val nodeNumbers = nodeList.zipWithIndex
    val nodeNumberMap = nodeNumbers.toMap
    val numberedEdges = edges.toList.map { case Edge(from, to, priority) =>
      (nodeNumberMap(from), priority, nodeNumberMap(to))
    }.sorted
    val edgeList = numberedEdges.map{ case (f, p, t) => s"$f --($p)--> $t"}
    val nodeNumberList = nodeNumbers.map{case(n, i) => s"$i: $n"}
    s"Graph(nodes: ${nodeNumberList.mkString("; ")} edges: ${edgeList.mkString(", ")}"
  }
}
