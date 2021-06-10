package magenta.graph

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GraphTest extends AnyFlatSpec with Matchers {

  val start = StartNode
  val one = ValueNode("one")
  val two = ValueNode("two")
  val three = ValueNode("three")
  val four = ValueNode("four")
  val five = ValueNode("five")
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
    val nodes = successors.filterValueNodes
    nodes.head should matchPattern{case ValueNode("one") =>}
    nodes(1) should matchPattern{case ValueNode("two") =>}
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
      start ~> one, start ~2~> two,
      one ~> two,
      two ~> end, two ~2~> three,
      three ~> end
    ))
  }

  it should "parallel join two dis-similar graphs together" in {
    val graph = Graph(StartNode ~> one, one ~> two, two ~> EndNode)
    val graph2 = Graph(StartNode ~> one, one ~> EndNode)
    val joinedGraph = graph joinParallel graph2
    joinedGraph should be(Graph(
      StartNode ~> one,
      one ~> two, one ~2~> EndNode,
      two ~> EndNode
    ))
  }

  it should "parallel join a one node graph to a shared two node graph" in {
    val graph = Graph(start ~> one, one ~> two, two ~> end)
    val graph2 = Graph(start ~> one, one ~> end)
    val joinedGraph = graph.joinParallel(graph2)
    joinedGraph should be(Graph(
      start ~> one,
      one ~> two, one ~2~> end,
      two ~> end
    ))
  }

  it should "join two graphs together in series" in {
    val graph = Graph(start ~> one, one ~> end)
    val graph2 = Graph(start ~> two, two ~> end)
    val mergedGraph = graph.joinSeries(graph2)
    mergedGraph.nodes.size should be(4)
    //mergedGraph.successors(StartNode).size should be(1)
    mergedGraph should be(Graph(start ~> one, one ~> two, two ~> end))
  }

  it should "retain priorities when merging non-trivial graphs in series" in {
    val graph = Graph(start ~> one, one ~> two, one ~2~> end, two ~> end)
    val graph2 = Graph(start ~> three, start ~2~> four, three ~> end, four ~> end)
    val joinedGraph = graph joinSeries(graph2)
    joinedGraph should be(Graph(
      start ~> one,
      one ~> two, one ~2~> three, one ~3~> four,
      two ~> three, two ~2~> four,
      three ~> end,
      four ~> end
    ))
  }

  it should "retain priorities when merging more complex examples in series" in {
    val graph = Graph(
      start ~> one,
      one ~> two, one ~2~> end, one ~3~> three,
      two ~> end, three ~> end
    )
    val graph2 = Graph(start ~> four, start ~2~> five, four ~> end, five ~> end)
    val joinedGraph = graph joinSeries graph2
    joinedGraph should be(Graph(
      start ~> one,
      one ~> two, one ~2~> four, one ~3~> five, one ~4~> three,
      two ~> four, two ~2~> five,
      three ~> four, three ~2~> five,
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
      start ~> one, start ~2~> two,
      one ~> three, one ~2~> four,
      two ~> three, two ~2~> four,
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

  "map" should "allow nodes to be replaced" in {
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
      ValueNode(List("one", "one")),
      ValueNode(List("two", "two")),
      ValueNode(List("three", "three")),
      ValueNode(List("four", "four"))
    ))
  }

  "flatMap" should "work with empty graphs" in {
    Graph.empty[Int] flatMap { case _ => Graph.empty[String] } shouldBe Graph.empty[String]
  }

  it should "join a small graph into the end of an empty graph" in {
    Graph.empty[Int] flatMap {
      case EndNode => Graph(4)
      case _ => Graph.empty[Int]
    } shouldBe Graph(4)
  }

  it should "noop when adding an empty graph onto the end of a graph" in {
    Graph(4) flatMap {
      case ValueNode(n) => Graph(n)
      case _ => Graph.empty[Int]
    } shouldBe Graph(4)
  }


  it should "allow nodes to be flatMapped to a series graph" in {
    val graph = Graph(start ~> one, one ~> end).joinParallel(Graph(start ~> two, two ~> end))
    val mappedGraph = graph.flatMap{
      case ValueNode(node) => Graph(start ~> ValueNode((node, 1)), ValueNode((node, 1)) ~> ValueNode((node, 2)), ValueNode((node, 2)) ~> end)
      case _ => Graph.empty[(String, Int)]
    }
    mappedGraph should be(Graph(
      start ~> ValueNode(("one", 1)), ValueNode(("one", 1)) ~> ValueNode(("one", 2)), ValueNode(("one", 2)) ~> end,
      start ~2~> ValueNode(("two", 1)), ValueNode(("two", 1)) ~> ValueNode(("two", 2)), ValueNode(("two", 2)) ~> end
    ))
  }

  it should "allow nodes to be flatMapped to a parallel graph" in {
    val graph = Graph(start ~> one, one ~> end).joinParallel(Graph(start ~> two, two ~> end))
    val mappedGraph = graph.flatMap{
      case ValueNode(node) =>
        Graph(
          start ~> ValueNode((node, 1)), ValueNode((node, 1)) ~> end,
          start ~2~> ValueNode((node, 2)), ValueNode((node, 2)) ~> end
        )
      case _ => Graph.empty[(String, Int)]
    }
    mappedGraph should be(Graph(
      start ~> ValueNode(("one", 1)), ValueNode(("one", 1)) ~> end,
      start ~2~> ValueNode(("one", 2)), ValueNode(("one", 2)) ~> end,
      start ~3~> ValueNode(("two", 1)), ValueNode(("two", 1)) ~> end,
      start ~4~> ValueNode(("two", 2)), ValueNode(("two", 2)) ~> end
    ))
  }

  it should "not allow a mid node to be replaced with an empty graph" in {
    // this is because sowing up the hole that is left is far harder than replacing nodes or even adding multiple nodes
    an [AssertionError] should be thrownBy {
      Graph(4) flatMap {
        case _ => Graph.empty[Int]
      }
    }
  }

  "joiningEdges" should "produce no edges for two empty graphs" in {
    Graph.joiningEdges(Graph.empty[Int], Graph.empty[Int]) shouldBe Set(StartNode ~> EndNode)
  }

  it should "produce a single edge for two simple graphs" in {
    Graph.joiningEdges(Graph(1), Graph(2)) shouldBe Set(ValueNode(1) ~> ValueNode(2))
  }

  it should "produce a set of edges for two more complex graphs" in {
    val graph = Graph(start ~> one, one ~> end).joinParallel(Graph(start ~> two, two ~> end))
    val graph2 = Graph(start ~> three, three ~> end).joinParallel(Graph(start ~> four, four ~> end))
    Graph.joiningEdges(graph, graph2) shouldBe Set(
      one ~> three, one ~2~> four, two ~> three, two ~2~> four
    )
  }

  "removeSuccesors" should "provide a copy of the graph with successors to a given node removed" in {
    val graph: Graph[Int] = Graph.from(Seq(1, 2, 3)).joinParallel(Graph.from(Seq(4, 5)))
    graph.removeSuccessorValueNodes(ValueNode(1)) shouldBe Graph.from(Seq(1)).joinParallel(Graph.from(Seq(4, 5)))
  }
}
