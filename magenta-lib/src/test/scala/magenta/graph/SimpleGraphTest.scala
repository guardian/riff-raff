package magenta.graph

import org.scalatest._

class SimpleGraphTest extends FlatSpec with ShouldMatchers {

  val start = StartNode
  val one = MidNode("one")
  val two = MidNode("two")
  val three = MidNode("three")
  val four = MidNode("four")
  val end = EndNode

  "SimpleGraph" should "parallel join two graphs together" in {
    val graph = Graph(start ~> one, one ~> end)
    val graph2 = Graph(start ~> two, two ~> end)
    val mergedGraph = graph.joinParallel(graph2)
    val successors = mergedGraph.successors(StartNode)
    successors.size should be(2)
    val nodes = successors.filterMidNodes.toList.sortBy(_.priority)
    nodes.head should matchPattern{case MidNode("one", 1) =>}
    nodes(1) should matchPattern{case MidNode("two", 2) =>}
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

  it should "join two graphs together in series" in {
    val graph = Graph(start ~> one, one ~> end)
    val graph2 = Graph(start ~> two, two ~> end)
    val mergedGraph = graph.joinSeries(graph2)
    mergedGraph.nodes.size should be(4)
    mergedGraph.successors(StartNode).size should be(1)
    mergedGraph should be(Graph(start ~> one, one ~> two, two ~> end))
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
      start ~> one, start ~> two.incPriority(1),
      one ~> three, one ~> four.incPriority(1),
      two.incPriority(1) ~> three, two.incPriority(1) ~> four.incPriority(1),
      three ~> end, four.incPriority(1) ~> end
    ))
  }

  it should "allow nodes to be mapped" in {
    val graph = Graph(start ~> one, one ~> end)
    val graph2 = Graph(start ~> two, two ~> end)
    val joinedGraph = graph.joinParallel(graph2)
    val graph3 = Graph(start ~> three, three ~> end)
    val graph4 = Graph(start ~> four, four ~> end)
    val joinedGraph2 = graph3.joinParallel(graph4)
    val mergedGraph = joinedGraph.joinSeries(joinedGraph2)
    val transformedGraph = mergedGraph.map { case MidNode(s, priority) =>
        MidNode(List(s, s), priority)
    }
    transformedGraph.nodes.size should be(6)
    transformedGraph.edges.size should be(mergedGraph.edges.size)
  }
}
