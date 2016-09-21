package magenta.input.resolver

import magenta.graph.{EndNode, Graph, MidNode, StartNode}
import magenta.input.Deployment
import org.scalatest.{FlatSpec, Matchers}

class DeploymentGraphActionFlatteningTest extends FlatSpec with Matchers {
  "flattenActions" should "flatten out the actions in a deployment graph" in {
    val deploymentWithActions =
      Deployment("bob", "autoscaling", List("stackName"), List("eu-west-1"), Some(List("action1", "action2")), "bob", "bob", Nil, Map.empty)
    val graph = Graph(deploymentWithActions)
    val flattenedGraph = DeploymentGraphActionFlattening.flattenActions(graph)

    val action1 = deploymentWithActions.copy(actions=Some(List("action1")))
    val action2 = deploymentWithActions.copy(actions=Some(List("action2")))
    flattenedGraph shouldBe Graph(
      StartNode ~> MidNode(action1), MidNode(action1) ~> MidNode(action2), MidNode(action2) ~> EndNode
    )
  }
}
