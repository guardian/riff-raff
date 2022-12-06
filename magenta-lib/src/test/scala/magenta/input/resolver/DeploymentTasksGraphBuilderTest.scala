package magenta.input.resolver

import cats.data.{NonEmptyList => NEL}
import magenta.graph.{EndNode, Graph, StartNode, ValueNode}
import magenta.input.Deployment
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DeploymentTasksGraphBuilderTest extends AnyFlatSpec with Matchers {
  val deployment = Deployment(
    "bob",
    "autoscaling",
    NEL.of("stackName"),
    NEL.of("eu-west-1"),
    None,
    NEL.of("deploy"),
    "bob",
    "bob",
    Nil,
    Map.empty
  )

  "buildGraph" should "build a simple graph for a single deployment" in {
    val graph = DeploymentGraphBuilder.buildGraph(List(deployment))
    graph shouldBe Graph(
      StartNode ~> ValueNode(deployment),
      ValueNode(deployment) ~> EndNode
    )
  }

  it should "build a parallel graph for two deployments with no dependencies" in {
    val deployment2 = deployment.copy(name = "bob2")
    val graph = DeploymentGraphBuilder.buildGraph(List(deployment, deployment2))
    graph shouldBe Graph(
      StartNode ~> ValueNode(deployment),
      ValueNode(deployment) ~> EndNode,
      StartNode ~ 2 ~> ValueNode(deployment2),
      ValueNode(deployment2) ~> EndNode
    )
  }

  it should "build a series graph for two deployments where one is dependent on the other" in {
    val deployment2 = deployment.copy(name = "bob2", dependencies = List("bob"))
    val graph = DeploymentGraphBuilder.buildGraph(List(deployment, deployment2))
    graph shouldBe Graph(
      StartNode ~> ValueNode(deployment),
      ValueNode(deployment) ~> ValueNode(deployment2),
      ValueNode(deployment2) ~> EndNode
    )
  }

  it should "build a graph with three deployments, one of which is a common dependency" in {
    val deployment2 = deployment.copy(name = "bob2", dependencies = List("bob"))
    val deployment3 = deployment.copy(name = "bob3", dependencies = List("bob"))
    val graph = DeploymentGraphBuilder.buildGraph(
      List(deployment, deployment2, deployment3)
    )
    graph shouldBe Graph(
      StartNode ~> ValueNode(deployment),
      ValueNode(deployment) ~> ValueNode(deployment2),
      ValueNode(deployment2) ~> EndNode,
      ValueNode(deployment) ~ 2 ~> ValueNode(deployment3),
      ValueNode(deployment3) ~> EndNode
    )
  }

  it should "build a simple graph when there are missing dependencies" in {
    val deploymentWithMissingDependency =
      deployment.copy(dependencies = List("missingDep"))
    val graph =
      DeploymentGraphBuilder.buildGraph(List(deploymentWithMissingDependency))
    graph shouldBe Graph(
      StartNode ~> ValueNode(deploymentWithMissingDependency),
      ValueNode(deploymentWithMissingDependency) ~> EndNode
    )
  }

  it should "build a graph with three deployments when there are missing dependencies" in {
    val deployment2 =
      deployment.copy(name = "bob2", dependencies = List("bob", "missingDep1"))
    val deployment3 =
      deployment.copy(name = "bob3", dependencies = List("bob", "missingDep2"))
    val graph = DeploymentGraphBuilder.buildGraph(
      List(deployment, deployment2, deployment3)
    )
    graph shouldBe Graph(
      StartNode ~> ValueNode(deployment),
      ValueNode(deployment) ~> ValueNode(deployment2),
      ValueNode(deployment2) ~> EndNode,
      ValueNode(deployment) ~ 2 ~> ValueNode(deployment3),
      ValueNode(deployment3) ~> EndNode
    )
  }

  it should "build a graph with a long chain of dependencies" in {
    val bob2a = deployment.copy(name = "bob2a", dependencies = List("bob"))
    val bob2b = deployment.copy(name = "bob2b", dependencies = List("bob"))
    val bob3 =
      deployment.copy(name = "bob3", dependencies = List("bob2a", "bob2b"))
    val bob4 = deployment.copy(name = "bob4", dependencies = List("bob3"))
    val bob5a = deployment.copy(name = "bob5a", dependencies = List("bob4"))
    val bob5b = deployment.copy(name = "bob5b", dependencies = List("bob4"))
    val deployments = List(deployment, bob2a, bob2b, bob3, bob4, bob5a, bob5b)
    val graph = DeploymentGraphBuilder.buildGraph(deployments)

    val bobNode = ValueNode(deployment)
    val bob2aNode = ValueNode(bob2a)
    val bob2bNode = ValueNode(bob2b)
    val bob3Node = ValueNode(bob3)
    val bob4Node = ValueNode(bob4)
    val bob5aNode = ValueNode(bob5a)
    val bob5bNode = ValueNode(bob5b)
    graph shouldBe Graph(
      StartNode ~> bobNode,
      bobNode ~> bob2aNode,
      bobNode ~ 2 ~> bob2bNode,
      bob2aNode ~> bob3Node,
      bob2bNode ~> bob3Node,
      bob3Node ~> bob4Node,
      bob4Node ~> bob5aNode,
      bob4Node ~ 2 ~> bob5bNode,
      bob5aNode ~> EndNode,
      bob5bNode ~> EndNode
    )
  }
}
