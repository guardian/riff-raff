package magenta.tasks

import com.amazonaws.services.s3.AmazonS3Client
import magenta.{Host, KeyRing, NamedStack, UnnamedStack}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, ShouldMatchers}
import scala.language.existentials

class TaskGraphTest extends FlatSpec with ShouldMatchers with MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  implicit val artifactClient = mock[AmazonS3Client]

  "TaskGraph" should "Convert a list to a graph" in {
    val graph = TaskGraph(threeSimpleTasks, UnnamedStack)
    graph.nodes.size should be(5)
    graph.edges.size should be(4)
    graph.toTaskList should be(threeSimpleTasks)
  }

  it should "label the first edge" in {
    val graph = TaskGraph(threeSimpleTasks, NamedStack("bobbins"))
    graph.start.outgoing.size should be(1)
    val label = graph.start.outgoing.head.taskGraphLabel.asInstanceOf[TaskGraphLabel]
    label should be(PathInfo("bobbins", 1))
  }

  it should "merge two graphs together" in {
    val graph = TaskGraph(threeSimpleTasks, NamedStack("bobbins"))
    val graph2 = TaskGraph(threeSimpleTasks, NamedStack("bobbins-the-second"))
    val mergedGraph = graph.joinParallel(graph2)
    val outgoing = mergedGraph.start.outgoing
    outgoing.size should be(2)
    val labels = outgoing.map(_.taskGraphLabel.asInstanceOf[TaskGraphLabel])
    labels should contain(PathInfo("bobbins", 1))
    labels should contain(PathInfo("bobbins-the-second", 2))
  }

  it should "merge two complex graphs together" in {
    val graph = TaskGraph(threeSimpleTasks, NamedStack("bobbins"))
    val graph2 = TaskGraph(threeSimpleTasks, NamedStack("bobbins-the-second"))
    val joinedGraph = graph.joinParallel(graph2)
    val graph3 = TaskGraph(threeSimpleTasks, NamedStack("bobbins-the-third"))
    val graph4 = TaskGraph(threeSimpleTasks, NamedStack("bobbins-the-fourth"))
    val joinedGraph2 = graph3.joinParallel(graph4)
    val mergedGraph = joinedGraph.joinParallel(joinedGraph2)
    val outgoing = mergedGraph.start.outgoing
    outgoing.size should be(4)
    val labels = outgoing.map(_.taskGraphLabel.asInstanceOf[TaskGraphLabel])
    labels should contain(PathInfo("bobbins", 1))
    labels should contain(PathInfo("bobbins-the-second", 2))
    labels should contain(PathInfo("bobbins-the-third", 3))
    labels should contain(PathInfo("bobbins-the-fourth", 4))

    joinedGraph.joinParallel(graph3).joinParallel(graph4) should be(mergedGraph)
  }

  val threeSimpleTasks = List(
    S3Upload("test-bucket", Seq()),
    SayHello(Host("testHost")),
    HealthcheckGrace(1000)
  )
}
