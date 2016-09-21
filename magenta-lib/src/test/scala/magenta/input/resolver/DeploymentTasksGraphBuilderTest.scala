package magenta.input.resolver

import magenta.graph.{EndNode, Graph, MidNode, StartNode}
import magenta.input.Deployment
import org.scalatest.{FlatSpec, ShouldMatchers}

class DeploymentTasksGraphBuilderTest extends FlatSpec with ShouldMatchers {
  val deployment = Deployment("bob", "autoscaling", List("stackName"), List("eu-west-1"), Some(List("deploy")), "bob", "bob", Nil, Map.empty)

  "buildGraph" should "build a simple graph for a single deployment" in {
    val graph = DeploymentGraphBuilder.buildGraph(List(deployment))
    graph shouldBe Graph(StartNode ~> MidNode(deployment), MidNode(deployment) ~> EndNode)
  }

  it should "build a parallel graph for two deployments with no dependencies" in {
    val deployment2 = deployment.copy(name="bob2")
    val graph = DeploymentGraphBuilder.buildGraph(List(deployment, deployment2))
    graph shouldBe Graph(
      StartNode ~> MidNode(deployment), MidNode(deployment) ~> EndNode,
      StartNode ~2~> MidNode(deployment2), MidNode(deployment2) ~> EndNode
    )
  }

  it should "build a series graph for two deployments where one is dependent on the other" in {
    val deployment2 = deployment.copy(name="bob2", dependencies = List("bob"))
    val graph = DeploymentGraphBuilder.buildGraph(List(deployment, deployment2))
    graph shouldBe Graph(
      StartNode ~> MidNode(deployment), MidNode(deployment) ~> MidNode(deployment2), MidNode(deployment2) ~> EndNode
    )
  }

  it should "build a graph with three deployments, one of which is a common dependency" in {
    val deployment2 = deployment.copy(name="bob2", dependencies = List("bob"))
    val deployment3 = deployment.copy(name="bob3", dependencies = List("bob"))
    val graph = DeploymentGraphBuilder.buildGraph(List(deployment, deployment2, deployment3))
    graph shouldBe Graph(
      StartNode ~> MidNode(deployment),
      MidNode(deployment) ~> MidNode(deployment2), MidNode(deployment2) ~> EndNode,
      MidNode(deployment) ~2~> MidNode(deployment3), MidNode(deployment3) ~> EndNode
    )
  }

  it should "build a simple graph when there are missing dependencies" in {
    val deploymentWithMissingDependency = deployment.copy(dependencies = List("missingDep"))
    val graph = DeploymentGraphBuilder.buildGraph(List(deploymentWithMissingDependency))
    graph shouldBe Graph(
      StartNode ~> MidNode(deploymentWithMissingDependency),
      MidNode(deploymentWithMissingDependency) ~> EndNode
    )
  }

  it should "build a graph with three deployments when there are missing dependencies" in {
    val deployment2 = deployment.copy(name="bob2", dependencies = List("bob", "missingDep1"))
    val deployment3 = deployment.copy(name="bob3", dependencies = List("bob", "missingDep2"))
    val graph = DeploymentGraphBuilder.buildGraph(List(deployment, deployment2, deployment3))
    graph shouldBe Graph(
      StartNode ~> MidNode(deployment),
      MidNode(deployment) ~> MidNode(deployment2), MidNode(deployment2) ~> EndNode,
      MidNode(deployment) ~2~> MidNode(deployment3), MidNode(deployment3) ~> EndNode
    )
  }
}
