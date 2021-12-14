package magenta.graph

import magenta.{DeploymentResources, Host, KeyRing, Region}
import magenta.fixtures._
import magenta.tasks.{S3Upload, SayHello, ShutdownTask}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.mockito.MockitoSugar
import software.amazon.awssdk.services.s3.S3Client

class DeploymentGraphTest extends AnyFlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  implicit val artifactClient: S3Client = mock[S3Client]

  "DeploymentGraph" should "Convert a list of tasks to a graph" in {
    val threeSimpleTasks = List(
      S3Upload(Region("eu-west-1"), "test-bucket", Seq()),
      SayHello(Host("testHost", app1, CODE.name, stack.name)),
      ShutdownTask(Host("testHost", app1, CODE.name, stack.name))
    )

    val graph = DeploymentGraph(threeSimpleTasks, "unnamed")
    graph.nodes.size should be(3)
    graph.edges.size should be(2)
    graph.nodes.filterValueNodes.head.value.tasks should be(threeSimpleTasks)
    DeploymentGraph.toTaskList(graph) should be(threeSimpleTasks)
  }
}
