package magenta.graph

import org.scalatest._

class GraphTest extends FlatSpec with ShouldMatchers {

  val start = StartNode
  val one = MidNode("one")
  val two = MidNode("two")
  val three = MidNode("three")
  val four = MidNode("four")
  val five = MidNode("five")
  val end = EndNode

  "Graph" should "correctly flatten a graph to a list" in {
    val graph = Graph(start ~> one, one ~> end)
    val graph2 = Graph(start ~> two, two ~> end)
    val mergedGraph = graph.joinParallel(graph2)
    mergedGraph.nodeList should be(List(start, one, two, end))
  }

  it should "correctly flatten a asymmetric graph to a list" in {
    val graph = Graph(start ~> one, one ~> two, two ~> end)
    val graph2 = Graph(start ~> one, one ~> end)
    val joinedGraph = graph.joinParallel(graph2)
    joinedGraph.nodeList should be(List(start, one, two, end))
  }

  it should "parallel join two graphs together" in {
    val graph = Graph(start ~> one, one ~> end)
    val graph2 = Graph(start ~> two, two ~> end)
    val mergedGraph = graph.joinParallel(graph2)
    val successors = mergedGraph.orderedSuccessors(StartNode)
    successors.size should be(2)
    val nodes = successors.filterMidNodes
    nodes.head should matchPattern{case MidNode("one") =>}
    nodes(1) should matchPattern{case MidNode("two") =>}
  }

  it should "parallel join two complex graphs together" in {
    val graph = Graph(start ~> one, one ~> end)
    val graph2 = Graph(start ~> two, two ~> end)
    val joinedGraph = graph.joinParallel(graph2)
    val graph3 = Graph(start ~> three, three ~> end)
    val graph4 = Graph(start ~> four, four ~> end)
    val joinedGraph2 = graph3.joinParallel(graph4)
    val mergedGraph = joinedGraph.joinParallel(joinedGraph2)
    val outgoing = mergedGraph.successors(StartNode)
    outgoing.size should be(4)
    joinedGraph.joinParallel(graph3).joinParallel(graph4) should be(mergedGraph)
  }

  it should "parallel join two graphs with shared nodes together" in {
    val graph = Graph(start ~> one, one ~> end)
    val graph2 = Graph(start ~> one, one ~> end)
    val joinedGraph = graph.joinParallel(graph2)
    joinedGraph should be(graph)
  }

  it should "parallel join two different graphs with shared nodes together" in {
    val graph = Graph(start ~> one, one ~> two, two ~> end)
    val graph2 = Graph(start ~> two, two ~> three, three ~> end)
    val joinedGraph = graph.joinParallel(graph2)
    joinedGraph should be(Graph(
      start ~> one, (start ~> two).incPriority(1),
      one ~> two,
      two ~> end, (two ~> three).incPriority(1),
      three ~> end
    ))
  }

  it should "parallel join two dis-similar graphs together" in {
    val graph = Graph(StartNode ~> one, one ~> two, two ~> EndNode)
    val graph2 = Graph(StartNode ~> one, one ~> EndNode)
    val joinedGraph = graph joinParallel graph2
    joinedGraph should be(Graph(
      StartNode ~> one,
      one ~> two, (one ~> EndNode).incPriority(1),
      two ~> EndNode
    ))
  }

  it should "parallel join a one node graph to a shared two node graph" in {
    val graph = Graph(start ~> one, one ~> two, two ~> end)
    val graph2 = Graph(start ~> one, one ~> end)
    val joinedGraph = graph.joinParallel(graph2)
    joinedGraph should be(Graph(
      start ~> one,
      one ~> two, (one ~> end).incPriority(1),
      two ~> end
    ))
  }

  it should "join two graphs together in series" in {
    val graph = Graph(start ~> one, one ~> end)
    val graph2 = Graph(start ~> two, two ~> end)
    val mergedGraph = graph.joinSeries(graph2)
    mergedGraph.nodes.size should be(4)
    mergedGraph.successors(StartNode).size should be(1)
    mergedGraph should be(Graph(start ~> one, one ~> two, two ~> end))
  }

  it should "retain priorities when merging non-trivial graphs in series" in {
    val graph = Graph(start ~> one, one ~> two, (one ~> end).incPriority(1), two ~> end)
    val graph2 = Graph(start ~> three, (start ~> four).incPriority(1), three ~> end, four ~> end)
    val joinedGraph = graph joinSeries graph2
    joinedGraph should be(Graph(
      start ~> one,
      one ~> two, (one ~> three).incPriority(1), (one ~> four).incPriority(2),
      two ~> three, (two ~> four).incPriority(1),
      three ~> end,
      four ~> end
    ))
  }

  it should "retain priorities when merging more complex examples in series" in {
    val graph = Graph(
      start ~> one,
      one ~> two, (one ~> end).incPriority(1), (one ~> three).incPriority(2),
      two ~> end, three ~> end
    )
    val graph2 = Graph(start ~> four, (start ~> five).incPriority(1), four ~> end, five ~> end)
    val joinedGraph = graph joinSeries graph2
    joinedGraph should be(Graph(
      start ~> one,
      one ~> two, (one ~> four).incPriority(1), (one ~> five).incPriority(2), (one ~> three).incPriority(3),
      two ~> four, (two ~> five).incPriority(1),
      three ~> four, (three ~> five).incPriority(1),
      four ~> end,
      five ~> end
    ))
  }

  it should "join two complex graphs together in series" in {
    val graph = Graph(start ~> one, one ~> end)
    val graph2 = Graph(start ~> two, two ~> end)
    val joinedGraph = graph.joinParallel(graph2)
    val graph3 = Graph(start ~> three, three ~> end)
    val graph4 = Graph(start ~> four, four ~> end)
    val joinedGraph2 = graph3.joinParallel(graph4)
    val mergedGraph = joinedGraph.joinSeries(joinedGraph2)
    mergedGraph.nodes.size should be(6)
    mergedGraph.edges should contain(one ~> three)
    mergedGraph should be(Graph(
      start ~> one, (start ~> two).incPriority(1),
      one ~> three, (one ~> four).incPriority(1),
      two ~> three, (two ~> four).incPriority(1),
      three ~> end, four ~> end
    ))
  }

  it should "noop when parallel joining to an empty graph" in {
    val graph = Graph(start ~> one, one ~> end)
    val mergedGraph = graph.joinParallel(Graph.empty)
    mergedGraph should be(graph)

    val mergedGraph2 = Graph.empty[String].joinParallel(graph)
    mergedGraph2 should be(graph)
  }

  it should "noop when series joining to an empty graph" in {
    val graph = Graph(start ~> one, one ~> end)
    val mergedGraph = graph.joinSeries(Graph.empty)
    mergedGraph should be(graph)

    val mergedGraph2 = Graph.empty[String].joinSeries(graph)
    mergedGraph2 should be(graph)
  }

  it should "allow nodes to be mapped" in {
    val graph = Graph(start ~> one, one ~> end)
    val graph2 = Graph(start ~> two, two ~> end)
    val joinedGraph = graph.joinParallel(graph2)
    val graph3 = Graph(start ~> three, three ~> end)
    val graph4 = Graph(start ~> four, four ~> end)
    val joinedGraph2 = graph3.joinParallel(graph4)
    val mergedGraph = joinedGraph.joinParallel(joinedGraph2)
    val transformedGraph = mergedGraph.map(s => List(s, s))
    transformedGraph.nodes.size should be(6)
    transformedGraph.edges.size should be(mergedGraph.edges.size)
    transformedGraph.orderedSuccessors(StartNode) should be (List(
      MidNode(List("one", "one")),
      MidNode(List("two", "two")),
      MidNode(List("three", "three")),
      MidNode(List("four", "four"))
    ))
  }
}
