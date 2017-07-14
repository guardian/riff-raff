package magenta.graph

import com.amazonaws.services.s3.AmazonS3Client
import magenta.{Host, KeyRing, Region}
import magenta.tasks.{ChangeSwitch, S3Upload, SayHello}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.mockito.MockitoSugar

class DeploymentGraphTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  implicit val artifactClient = mock[AmazonS3Client]

  "DeploymentGraph" should "Convert a list of tasks to a graph" in {
    val graph = DeploymentGraph(threeSimpleTasks, "unnamed")
    graph.nodes.size should be(3)
    graph.edges.size should be(2)
    graph.nodes.filterValueNodes.head.value.tasks should be(threeSimpleTasks)
    DeploymentGraph.toTaskList(graph) should be(threeSimpleTasks)
  }

  val threeSimpleTasks = List(
    S3Upload(Region("eu-west-1"), "test-bucket", Seq()),
    SayHello(Host("testHost")),
    ChangeSwitch(Host("testHost"), "http", 8080, "switchPath", "bobbinSwitch", desiredState = true)
  )

}
