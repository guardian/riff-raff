package magenta.graph

/** A Node corresponds to a node in a graph that can be joined by edges
  *
  * @tparam T
  *   The type of the underlying values held by a graph
  */
sealed trait Node[+T] {
  def ~>[R >: T](to: Node[R]): Edge[R] = Edge(this, to)
  def ~[R >: T](priority: Int): EdgeBuilder[R] = EdgeBuilder(this, priority)

  /** Returns the underlying value for this node.
    *
    * @return
    *   Returns Some(value) or None if this node has no underlying value.
    */
  def maybeValue: Option[T] = None
}

/** A StartNode indicates the entry point to a graph */
case object StartNode extends Node[Nothing]

/** A ValueNode is a node in a graph that holds a value.
  *
  * @param value
  *   The underlying value held by this node
  * @tparam T
  *   The type of the underlying values held by a graph
  */
case class ValueNode[T](value: T) extends Node[T] {
  override def maybeValue: Option[T] = Some(value)
}

/** The EndNode indicates the final node in a graph */
case object EndNode extends Node[Nothing]

/** EdgeBuilder should not be used directly. It is used (alongside the ~ method
  * in [[magenta.graph.Node]]) to construct the edge DSL of the form `~3~>` for
  * an edge with priority three.
  *
  * @param from
  *   The node from which this edgebuilder will build an edge.
  * @param priority
  *   The priority with which this edge builder will build an edge.
  * @tparam T
  *   The type of the underlying values held by a graph.
  */
case class EdgeBuilder[+T](from: Node[T], priority: Int) {
  def ~>[R >: T](to: Node[R]): Edge[R] = Edge(from, to, priority)
}

/** Represents a directional edge in a graph with an accompanying priority. In
  * the case where there are multiple edges in a graph which have the same from
  * Node the priority can be used in order to deterministically traverse the
  * graph.
  *
  * @param from
  *   The start Node of this directional Edge
  * @param to
  *   The finish Node of this directional Edge
  * @param priority
  *   The priority of this Edge
  * @tparam T
  *   The type of the underlying values held by a graph.
  */
case class Edge[+T](from: Node[T], to: Node[T], priority: Int = 1) {
  def ~=[R >: T](other: Edge[R]) = from == other.from && to == other.to
}

object Graph {

  /** Create a graph using edges provided as var args
    *
    * @param edges
    *   The edges to build the graph from
    * @tparam T
    *   The type of the underlying values held by a graph.
    * @return
    *   A graph containing the provided edges
    */
  def apply[T](edges: Edge[T]*): Graph[T] = Graph(edges.toSet)

  /** Constructs a graph with a singleton value.
    *
    * @param singleton
    *   The singleton value.
    * @tparam T
    *   The type of the underlying value held by this singleton graph.
    * @return
    *   A singleton graph with the structure `StartNode ~> ValueNode(singleton)
    *   ~> EndNode`
    */
  def apply[T](singleton: T): Graph[T] = {
    val node = ValueNode(singleton)
    Graph(StartNode ~> node, node ~> EndNode)
  }

  /** Constructs a graph containing these values in series. For example -
    * providing the strings `"one"` and `"two"` as parameters then you'll get a
    * graph such as `StartNode ~> ValueNode("one") ~> ValueNode("two") ~>
    * EndNode`.
    *
    * @param values
    *   values to construct series graph
    * @tparam T
    *   The type of the underlying values held by a graph.
    * @return
    *   series graph containing the provided values
    */
  def from[T](values: Seq[T]): Graph[T] = {
    val nodes: Seq[Node[T]] =
      StartNode +: values.map(ValueNode.apply) :+ EndNode
    val edges =
      nodes.sliding(2).map { window => window.head ~> window.tail.head }.toSet
    Graph(edges)
  }

  /** Convenience method to create a graph with no value nodes */
  def empty[T] = Graph[T](StartNode ~> EndNode)

  /** Generate the set of edges that are needed to join the end of the from
    * Graph to the start of the to Graph
    *
    * @param from
    *   The graph whose end you wish to join
    * @param to
    *   The graph whose start you wish to join
    * @return
    *   Set of joining edges that would join these two graphs together
    */
  private[graph] def joiningEdges[R](
      from: Graph[R],
      to: Graph[R]
  ): Set[Edge[R]] = {
    val fromEndEdges = from.incoming(EndNode)
    val toStartEdges = to.orderedOutgoing(StartNode)
    fromEndEdges.foldLeft(Set.empty[Edge[R]]) { case (acc, endEdge) =>
      val existing = from.orderedOutgoing(endEdge.from)
      val targetEdges =
        toStartEdges.map(startEdge => Edge(endEdge.from, startEdge.to))
      val replacement = existing.replace(endEdge, targetEdges).reprioritise
      acc -- existing ++ replacement
    }
  }
}

/** A relatively simple graph implementation with a specific purpose in mind.
  * That is, this is not a general purpose graph datatype.
  *
  * It is designed to allow multiple parts of a deployment to be executed
  * simultaneously. As a datatype it holds only a set of directional
  * [[magenta.graph.Edge]]s that link two nodes together. A graph does not know
  * directly about [[magenta.graph.Node]]s. Instead the graph contains the set
  * of all of the nodes that a referenced by all edges.
  *
  * The purpose of this graph led to the design having these properties:
  *   - a unique node only appears once in a graph - merging together two graphs
  *     that contain the same unique node will result in a single node with
  *     additional edges
  *   - it is possible to traverse all nodes in the graph in a deterministic
  *     order
  *
  * The following must be true about the structure of a graph and is asserted
  * when a new Graph is created
  *   - each graph must contain a StartNode and an EndNode
  *   - no two edges from a single node has the same priority
  *   - each node in a graph must be reachable by following edges from the
  *     StartNode
  *   - for each node it must possible to follow edges to the EndNode
  *   - the graph must be acyclic
  *
  * @param edges
  *   The edges that make up the structure of this graph
  * @tparam T
  *   The type of the underlying values held by a graph.
  */
case class Graph[T](edges: Set[Edge[T]]) {
  val nodes = edges.map(_.from) ++ edges.map(_.to)
  val valueNodes = nodes.filterValueNodes

  /** Error is a utility class for collecting errors */
  case class Error(messages: List[String]) {
    var asOption: Option[Error] = if (messages.isEmpty) None else Some(this)
    def +(other: Error) = Error(messages ::: other.messages)
  }
  private object Error {
    def apply(message: String): Error = Error(List(message))
    def apply(isError: => Boolean, message: => String): Error =
      if (isError) Error(message) else empty
    val empty = apply(Nil)
  }

  /** Traverse this graph starting at the StartNode until we reach the EndNode.
    *
    * This produces a list of all of the nodes in the graph in a
    * deterministically produced order according to the priorities on each edge.
    * The traversal starts at the StartNode and then follows each outgoing edge
    * in order until a node is found that has an incoming edge that has not yet
    * been traversed.
    *
    * The result can either be the list of nodes or an Error if we discover that
    * a graph is not acyclic or if there are nodes that cannot reach the
    * EndNode.
    */
  val traverse: Either[Error, List[Node[T]]] = {
    def traverseFrom(
        node: Node[T],
        traversed: Set[Edge[T]]
    ): Either[Error, (List[Node[T]], Set[Edge[T]])] = {
      val incoming: Set[Edge[T]] = this.incoming(node)
      if ((incoming -- traversed).nonEmpty) {
        // if there are some incoming edges to this node that we haven't yet traversed then ignore this node - we'll be back
        Right(Nil, traversed)
      } else {
        // if we've traversed all of the incoming edges then follow all the outgoing edges
        val outgoing = this.orderedOutgoing(node)

        if (outgoing.isEmpty && node != EndNode)
          Left(Error(s"Node $node has no outgoing edges"))
        if (outgoing.exists(traversed.contains))
          Left(
            Error(
              s"Graph not acyclic - already traversed outgoing edges from $node"
            )
          )

        outgoing.foldLeft[Either[Error, (List[Node[T]], Set[Edge[T]])]](
          Right(List(node), traversed)
        ) {
          case (Right((nodeAcc, edgeAcc)), successor) =>
            val next = traverseFrom(successor.to, edgeAcc + successor)
            next.right.map { result =>
              (nodeAcc ::: result._1, edgeAcc ++ result._2)
            }
          case (error, _) => error
        }
      }
    }
    traverseFrom(StartNode, Set.empty).right.map(_._1)
  }

  /** Check whether this Graph follows the constraints that are set out in the
    * [[magenta.graph.Graph]] documentation.
    *
    * Any errors with the Graph will be returned here.
    */
  val constraintErrors: Option[Error] = {
    def checkStartAndEndNodes = Error(
      !nodes.contains(StartNode),
      "No start node"
    ) + Error(!nodes.contains(EndNode), "No end node")
    def checkEdgePriorties = nodes.foldLeft(Error.empty) { (error, node) =>
      val priorities = outgoing(node).toList.map(_.priority)
      error + Error(
        priorities.size != priorities.toSet.size,
        s"Multiple outgoing edges have same priority from node $node (${priorities.mkString(",")}"
      )
    }
    def checkTraversable = {
      traverse.right
        .flatMap { nodeListResult =>
          if (nodeListResult.toSet.size != nodes.size) {
            Left(
              Error(
                s"Graph was not fully traversed by nodeList (expected to traverse ${nodes.size} nodes but actually traversed ${nodeListResult.toSet.size})"
              )
            )
          } else {
            Right(nodeListResult)
          }
        }
        .left
        .getOrElse(Error.empty)
    }

    val errors = checkStartAndEndNodes +
      checkEdgePriorties +
      checkTraversable

    errors.asOption
  }

  assert(
    constraintErrors.isEmpty,
    s"Graph isn't valid: ${constraintErrors.get.messages.mkString(", ")}"
  )

  /** true if this graph only contains a StartNode and EndNode */
  val isEmpty = valueNodes.isEmpty

  /** returns the [[magenta.graph.ValueNode]] for a given underlying node */
  def get(node: T): ValueNode[T] = valueNodes.find(_.value == node).get

  /** returns edges going from this node to another */
  def outgoing(node: Node[T]): Set[Edge[T]] = edges.filter(_.from == node)

  /** returns nodes that can be directly reached from this node */
  def successors(node: Node[T]): Set[Node[T]] = outgoing(node).map(_.to)

  /** returns edges going from this node to another ordered by the priority of
    * the edge
    */
  def orderedOutgoing(node: Node[T]): List[Edge[T]] =
    outgoing(node).toList.sortBy(_.priority)

  /** returns node that can be directly reached from this node ordered by the
    * priority of the edges
    */
  def orderedSuccessors(node: Node[T]): List[Node[T]] =
    orderedOutgoing(node).map(_.to)

  /** returns edges that arrive at this node from another */
  def incoming(node: Node[T]): Set[Edge[T]] = edges.filter(_.to == node)

  /** returns nodes that can reach this node */
  def predecessors(node: Node[T]): Set[Node[T]] = incoming(node).map(_.from)

  /** Replace a single node in the graph with another node of the same type.
    *
    * @param node
    *   the node you wish to replace
    * @param withNode
    *   the node to substitute for `node`
    * @return
    *   newly constructed graph with the substituted node
    */
  def replace(node: Node[T], withNode: Node[T]): Graph[T] = {
    val newEdges = edges.map {
      case Edge(from, to, priority) if from == node =>
        Edge(withNode, to, priority)
      case Edge(from, to, priority) if to == node =>
        Edge(from, withNode, priority)
      case other => other
    }
    Graph(newEdges)
  }

  /** Builds a new graph by applying a function to each value in this graph.
    * This retains the structure of the graph but replaces the values of any
    * [[magenta.graph.ValueNode]]. You cannot map two distinct values in the
    * graph to a single value as this would alter the structure of the graph. *
    * \@param f function to apply to each existing value
    * @tparam R
    *   the type of the value in the new graph
    * @return
    *   new graph with the same structure but values from the function
    */
  def map[R](f: T => R): Graph[R] = {
    val newNodeMap: Map[Node[T], Node[R]] = (valueNodes.map { currentNode =>
      currentNode -> ValueNode(f(currentNode.value))
    }.toMap ++ Map[Node[T], Node[R]](
      StartNode -> StartNode,
      EndNode -> EndNode
    )).toMap
    assert(
      newNodeMap.size == newNodeMap.values.toSet.size,
      "Source nodes must be mapped onto unique target nodes"
    )
    val newEdges = edges.map { oldEdge =>
      Edge(newNodeMap(oldEdge.from), newNodeMap(oldEdge.to), oldEdge.priority)
    }
    Graph(newEdges)
  }

  /** Build a new graph by applying a function to each node in this graph and
    * combining the results.
    *
    * Keeps the shape of this graph whilst mapping each node to a graph. This
    * means one can replace the [[magenta.graph.StartNode]],
    * [[magenta.graph.EndNode]] or [[magenta.graph.ValueNode]] with multiple
    * nodes. The incoming and outgoing edges of each node of this graph will be
    * replaced by edges that map to the start and end nodes of the graph
    * returned from f.
    *
    * An identity mapping for flatMap would be start and end nodes to empty
    * graph and mid nodes to a single value graph containing the value of the
    * node. e.g.
    * {{{
    * graph flatMap {
    *   case ValueNode(n) => Graph(n)
    *   case _ => Graph.empty
    * }
    * }}}
    *
    * It is not possible to map a [[magenta.graph.ValueNode]] to an empty graph.
    *
    * @param f
    *   the function to apply to each element
    * @return
    *   a new graph with the graphs substituted
    */
  def flatMap[R](f: Node[T] => Graph[R]): Graph[R] = {
    val nodeToGraphMap: Map[Node[T], Graph[R]] = nodes.map(n => n -> f(n)).toMap
    // find all of the edges that we won't adjoin to another graph
    val allInternalEdges = nodeToGraphMap.flatMap {
      case (StartNode, graph)    => graph.edges -- graph.incoming(EndNode)
      case (ValueNode(_), graph) =>
        assert(
          !graph.isEmpty,
          "It is not possible to flatMap a MidNode to an empty graph"
        )
        graph.edges -- graph.incoming(EndNode) -- graph.outgoing(StartNode)
      case (EndNode, graph) => graph.edges -- graph.outgoing(StartNode)
    }.toSet

    // sort the edges by priority - this means we can re-calculate the priorities correctly
    val joiningEdges = edges.toList
      .sortBy(_.priority)
      .foldLeft(Set.empty[Edge[R]]) { case (acc, oldEdge) =>
        val fromGraph = nodeToGraphMap(oldEdge.from)
        val toGraph = nodeToGraphMap(oldEdge.to)
        // get the joining edges
        val newEdges = Graph
          .joiningEdges(fromGraph, toGraph)
          // deduplicate any edges that we've already generated
          .filterNot(e1 => acc.exists(e1 ~= _))
          // fix up the priorities
          .map { newEdge =>
            val maxPriority = acc
              .filter(_.from == newEdge.from)
              .map(_.priority)
              .reduceOption(Math.max)
              .getOrElse(0)
            newEdge.copy(priority = newEdge.priority + maxPriority)
          }
        acc ++ newEdges
      }

    Graph(allInternalEdges ++ joiningEdges)
  }

  /** Add two graphs together so they are traversed in parallel. Outgoing edges
    * from the StartNode of either graph will be combined to be outgoing edges
    * from the StartNode in the resulting graph. Likewise the incoming edges to
    * the EndNode of both graphs will be combined. In fact any duplicate nodes
    * between the two graphs will be merged together and outgoing edges will be
    * re-prioritised appropriately. Duplicate edges (from and to the same node)
    * will be removed.
    *
    * The priorities in the resulting graph will be such that the priorities of
    * the edges from the `other` graph will be lower than the priorities of this
    * graph.
    *
    * @param other
    *   graph to add to this graph
    * @return
    *   merged graph containing the edges from both
    */
  def joinParallel(other: Graph[T]): Graph[T] = {
    if (other.isEmpty) this
    else if (isEmpty) other
    else {
      val edgesToMerge = other.edges.filterNot(e1 => edges.exists(e1 ~= _))
      val otherEdges = edgesToMerge.map { edge =>
        val maxPriority = edges
          .filter(_.from == edge.from)
          .map(_.priority)
          .reduceOption(Math.max)
          .getOrElse(0)
        edge.copy(priority = edge.priority + maxPriority)
      }
      Graph(edges ++ otherEdges)
    }
  }

  /** Add two graphs together so they are traversed in series. In essence it
    * will be as if an edge from the EndNode of `this` graph to the StartNode of
    * the `other` graph has been created. In reality edges will be created from
    * all predecessors of the EndNode of the first graph to all successors of
    * the StartNode in the second graph.
    *
    * Duplicate nodes between the two graphs will not work as de-duplicating
    * such nodes will inevitably result in a cyclic graph.
    *
    * @param other
    *   graph to add to this graph
    * @return
    *   merged graph containing the edges from both
    */
  def joinSeries(other: Graph[T]): Graph[T] = flatMap {
    case StartNode        => Graph.empty
    case ValueNode(value) => Graph(value)
    case EndNode          => other
  }

  /** Returns a deterministically ordered set of nodes using
    * [[magenta.graph.Graph.traverse]].
    *
    * In theory this can throw an exception if the graph cannot be traversed. In
    * reality this is checked at the time the graph is created and should never
    * happen.
    *
    * @return
    * @throws IllegalStateException
    *   if the graph cannot be traversed
    */
  def nodeList = {
    traverse fold (
      { error =>
        throw new IllegalStateException(s"Couldn't traverse graph: $error")
      },
      identity
    )
  }

  def allSuccesors(node: Node[T]): Set[Node[T]] = {
    successors(node) match {
      case s if s.isEmpty  => Set.empty
      case nodesSuccessors =>
        nodesSuccessors ++ nodesSuccessors.flatMap(allSuccesors)
    }
  }

  /** Returns a copy of the graph with all successor of the given node removed
    */
  def removeSuccessorValueNodes(node: ValueNode[T]): Graph[T] = {
    val toBeRemoved = allSuccesors(node).filter(_ != EndNode)
    Graph(
      edges.filter(edge =>
        !(toBeRemoved.contains(edge.from) || toBeRemoved.contains(edge.to))
      ) + Edge(node, EndNode)
    )
  }

  lazy val toList: List[T] = nodeList.flatMap(_.maybeValue)

  override def toString: String = {
    val identifiers = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    val nodeNumbers = (nodeList ::: (nodes -- nodeList).toList).zipWithIndex
    val nodeNumberMap: Map[Node[T], String] = nodeNumbers.toMap.view.mapValues {
      case index if index < identifiers.length => identifiers(index).toString
      case index => (index - identifiers.length).toString
    }.toMap
    val numberedEdges = edges.toList.map { case Edge(from, to, priority) =>
      (nodeNumberMap(from), priority, nodeNumberMap(to))
    }.sorted
    val edgeList = numberedEdges.map { case (f, p, t) => s"$f ~$p~> $t" }
    val nodeNumberList = nodeNumbers.map { case (n, i) =>
      s"${nodeNumberMap(n)}: $n"
    }
    s"Graph(nodes: ${nodeNumberList.mkString("; ")} edges: ${edgeList.mkString(", ")}"
  }
}
