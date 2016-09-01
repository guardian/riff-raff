package magenta.graph

import com.amazonaws.services.s3.AmazonS3Client
import magenta.{Host, KeyRing}
import magenta.tasks.{HealthcheckGrace, S3Upload, SayHello}
import org.scalatest.{FlatSpec, ShouldMatchers}
import org.scalatest.mock.MockitoSugar

class DeploymentGraphTest extends FlatSpec with ShouldMatchers with MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  implicit val artifactClient = mock[AmazonS3Client]

  "DeploymentGraph" should "Convert a list of tasks to a graph" in {
    val graph = DeploymentGraph(threeSimpleTasks, "unnamed")
    graph.nodes.size should be(3)
    graph.edges.size should be(2)
    graph.nodes.filterDeploymentNodes.head.tasks should be(threeSimpleTasks)
    graph.toTaskList should be(threeSimpleTasks)
  }

  it should "merge two graphs together" in {
    val graph = DeploymentGraph(threeSimpleTasks, "bobbins")
    val graph2 = DeploymentGraph(threeSimpleTasks, "bobbins-the-second")
    val mergedGraph = graph.joinParallel(graph2)
    val successors = mergedGraph.successors(StartNode)
    successors.size should be(2)
    val deploymentNodes = successors.filterDeploymentNodes.toList.sortBy(_.priority)
    deploymentNodes.head should matchPattern{case DeploymentNode(_, "bobbins", 1) =>}
    deploymentNodes(1) should matchPattern{case DeploymentNode(_, "bobbins-the-second", 2) =>}
  }

  it should "merge two complex graphs together" in {
    val graph = DeploymentGraph(threeSimpleTasks, "bobbins")
    val graph2 = DeploymentGraph(threeSimpleTasks, "bobbins-the-second")
    val joinedGraph = graph.joinParallel(graph2)
    val graph3 = DeploymentGraph(threeSimpleTasks, "bobbins-the-third")
    val graph4 = DeploymentGraph(threeSimpleTasks, "bobbins-the-fourth")
    val joinedGraph2 = graph3.joinParallel(graph4)
    val mergedGraph = joinedGraph.joinParallel(joinedGraph2)
    val outgoing = mergedGraph.successors(StartNode)
    outgoing.size should be(4)
    joinedGraph.joinParallel(graph3).joinParallel(graph4) should be(mergedGraph)
  }

  val threeSimpleTasks = List(
    S3Upload("test-bucket", Seq()),
    SayHello(Host("testHost")),
    HealthcheckGrace(1000)
  )

}
