package magenta.graph

import magenta.{Host, KeyRing, Region}
import magenta.tasks.{ChangeSwitch, S3Upload, SayHello}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.mockito.MockitoSugar
import magenta.fixtures._
import software.amazon.awssdk.services.s3.S3Client

class DeploymentGraphTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  implicit val artifactClient: S3Client = mock[S3Client]

  "DeploymentGraph" should "Convert a list of tasks to a graph" in {
    val graph = DeploymentGraph(threeSimpleTasks, "unnamed")
    graph.nodes.size should be(3)
    graph.edges.size should be(2)
    graph.nodes.filterValueNodes.head.value.tasks should be(threeSimpleTasks)
    DeploymentGraph.toTaskList(graph) should be(threeSimpleTasks)
  }

  val threeSimpleTasks = List(
    S3Upload(Region("eu-west-1"), "test-bucket", Seq()),
    SayHello(Host("testHost", app1, CODE.name, stack.name)),
    ChangeSwitch(Host("testHost", app1, CODE.name, stack.name), "http", 8080, "switchPath", "bobbinSwitch", desiredState = true)
  )

}
