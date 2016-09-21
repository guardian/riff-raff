package magenta.graph

sealed trait Node[+T] {
  def ~>[R >: T](to: Node[R]): Edge[R] = Edge(this, to)
  def ~[R >: T](priority: Int): EdgeBuilder[R] = EdgeBuilder(this, priority)
  def maybeValue: Option[T] = None
}
case object StartNode extends Node[Nothing]
case class MidNode[T](value: T) extends Node[T] {
  override def maybeValue: Option[T] = Some(value)
}
case object EndNode extends Node[Nothing]

case class EdgeBuilder[+T](from: Node[T], priority: Int) {
  def ~>[R >: T](to: Node[R]): Edge[R] = Edge(from, to, priority)
}

case class Edge[+T](from: Node[T], to: Node[T], priority: Int = 1) {
  def ~=[R >: T](other: Edge[R]) = from == other.from && to == other.to
}

object Graph {
  def apply[T](edges: Edge[T]*): Graph[T] = Graph(edges.toSet)

  def apply[T](singleton: T): Graph[T] = Graph(StartNode ~> MidNode(singleton), MidNode(singleton) ~> EndNode)

  def from[T](values: T*): Graph[T] = {
    val nodes: Seq[Node[T]] = StartNode +: values.map(MidNode.apply) :+ EndNode
    val edges = nodes.sliding(2).map{ window => window.head ~> window.tail.head }.toSet
    Graph(edges)
  }

  def empty[T] = Graph[T](StartNode ~> EndNode)

  /**
    * Generate the set of edges that are needed to join the end of the from graph to the start of the to graph
 *
    * @param from The graph whose end you wish to join
    * @param to The graph whose start you wish to join
    * @return Set of joining edges that would join these two graphs together
    */
  private[graph] def joiningEdges[R](from: Graph[R], to: Graph[R]): Set[Edge[R]] = {
    val fromEndEdges = from.incoming(EndNode)
    val toStartEdges = to.orderedOutgoing(StartNode)
    fromEndEdges.foldLeft(Set.empty[Edge[R]]) { case (acc, endEdge) =>
      val existing = from.orderedOutgoing(endEdge.from)
      val targetEdges = toStartEdges.map(startEdge => Edge(endEdge.from, startEdge.to))
      val replacement = existing.replace(endEdge, targetEdges).reprioritise
      acc -- existing ++ replacement
    }
  }
}

case class Graph[T](edges: Set[Edge[T]]) {
  val nodes = edges.map(_.from) ++ edges.map(_.to)
  private val underlyingNodes = nodes.flatMap(_.maybeValue)
  private val dataNodes = nodes.filterMidNodes

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

  /**
    * Flat map keeps the shape of this graph whilst mapping each node to a graph. This means one can replace the Start,
    * End or Mid nodes with multiple nodes. The incoming and outgoing edges of each node of this graph will be replaced
    * by edges that map to the start and end nodes of the graph returned from f.
    *
    * An identity mapping for flatMap would be start and end nodes to empty map and mid nodes to a single value graph
    * containing the value of the node.
    *
    * It is not possible to map a mid node to an empty node.
    * @param f the function to apply to each element
    * @return a new graph with the graphs substituted
    */
  def flatMap[R](f: Node[T] => Graph[R]): Graph[R] = {
    val nodeToGraphMap: Map[Node[T], Graph[R]] = nodes.map(n => n -> f(n)).toMap
    // find all of the edges that we won't adjoin to another graph
    val allInternalEdges = nodeToGraphMap.flatMap{
      case (StartNode, graph) => graph.edges -- graph.incoming(EndNode)
      case (MidNode(_), graph) =>
        assert(!graph.isEmpty, "It is not possible to flatMap a MidNode to an empty graph")
        graph.edges -- graph.incoming(EndNode) -- graph.outgoing(StartNode)
      case (EndNode, graph) => graph.edges -- graph.outgoing(StartNode)
    }.toSet

    // sort the edges by priority - this means we can re-calculate the priorities correctly
    val joiningEdges = edges.toList.sortBy(_.priority)
      .foldLeft(Set.empty[Edge[R]]) { case(acc, oldEdge) =>

      val fromGraph = nodeToGraphMap(oldEdge.from)
      val toGraph = nodeToGraphMap(oldEdge.to)
      // get the joining edges
      val newEdges = Graph.joiningEdges(fromGraph, toGraph)
        // deduplicate any edges that we've already generated
        .filterNot(e1 => acc.exists(e1 ~= _))
        // fix up the priorities
        .map{ newEdge =>
          val maxPriority = acc.filter(_.from == newEdge.from).map(_.priority).reduceOption(Math.max).getOrElse(0)
          newEdge.copy(priority = newEdge.priority + maxPriority)
        }
      acc ++ newEdges
    }

    Graph(allInternalEdges ++ joiningEdges)
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

  def joinSeries(other: Graph[T]): Graph[T] = flatMap {
    case StartNode => Graph.empty
    case MidNode(value) => Graph(value)
    case EndNode => other
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
    val identifiers = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    val nodeNumbers = (nodeList ::: (nodes -- nodeList).toList).zipWithIndex
    val nodeNumberMap: Map[Node[T], String] = nodeNumbers.toMap.mapValues{
      case index if index < identifiers.length => identifiers(index).toString
      case index => (index - identifiers.length).toString
    }
    val numberedEdges = edges.toList.map { case Edge(from, to, priority) =>
      (nodeNumberMap(from), priority, nodeNumberMap(to))
    }.sorted
    val edgeList = numberedEdges.map{ case (f, p, t) => s"$f ~$p~> $t"}
    val nodeNumberList = nodeNumbers.map{case(n, i) => s"${nodeNumberMap(n)}: $n"}
    s"Graph(nodes: ${nodeNumberList.mkString("; ")} edges: ${edgeList.mkString(", ")}"
  }
}
