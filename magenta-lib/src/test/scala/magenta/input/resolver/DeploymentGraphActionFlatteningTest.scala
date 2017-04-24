package magenta.input.resolver

import cats.data.{NonEmptyList => NEL}
import magenta.graph.{EndNode, Graph, StartNode, ValueNode}
import magenta.input.Deployment
import org.scalatest.{FlatSpec, Matchers}

class DeploymentGraphActionFlatteningTest extends FlatSpec with Matchers {
  "flattenActions" should "flatten out the actions in a deployment graph" in {
    val deployment =
      Deployment("bob",
                 "autoscaling",
                 NEL.of("stackName"),
                 NEL.of("eu-west-1"),
                 NEL.of("action1", "action2"),
                 "bob",
                 "bob",
                 Nil,
                 Map.empty)
    val graph = Graph(deployment)
    val flattenedGraph = DeploymentGraphActionFlattening.flattenActions(graph)

    val action1 = deployment.copy(actions = NEL.of("action1"))
    val action2 = deployment.copy(actions = NEL.of("action2"))
    flattenedGraph shouldBe Graph(
      StartNode ~> ValueNode(action1),
      ValueNode(action1) ~> ValueNode(action2),
      ValueNode(action2) ~> EndNode
    )
  }
}
