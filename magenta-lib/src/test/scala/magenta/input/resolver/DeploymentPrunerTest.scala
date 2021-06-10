package magenta.input.resolver

import cats.data.{NonEmptyList => NEL}
import magenta.input.{Deployment, DeploymentKey}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DeploymentPrunerTest extends AnyFlatSpec with Matchers {
  import DeploymentPruner._
  val testDeployment = Deployment("testName", "testType", NEL.of("testStack"), NEL.of("testRegion"),
    NEL.of("testAction"), "testName", "testName", Nil, Map.empty)
  val testStackAndRegionPruner = StackAndRegion("testStack", "testRegion")

  "StackAndRegion pruner" should "return None when either the region or stack isn't specified" in {
    testStackAndRegionPruner(testDeployment.copy(stacks = NEL.of("differentStack"))) shouldBe None
    testStackAndRegionPruner(testDeployment.copy(regions = NEL.of("differentRegion"))) shouldBe None
    testStackAndRegionPruner(testDeployment.copy(stacks = NEL.of("differentStack"), regions = NEL.of("differentRegion"))) shouldBe None
  }

  it should "return the unmodified deployment when it has only the specified stack and region" in {
    testStackAndRegionPruner(testDeployment) shouldBe Some(testDeployment)
  }

  it should "return a modified deployment containing just the specified region and stack when there are multiple stacks and regions" in {
    testStackAndRegionPruner(testDeployment.copy(
      stacks = NEL.of("testStack1", "testStack", "testStack2"),
      regions = NEL.of("testRegion1", "testRegion", "testRegion2")
    )) shouldBe Some(testDeployment)
  }

  val testKeysPruner = Keys(List(DeploymentKey(testDeployment)))

  "Keys pruner" should "return None when the name, action, region or stack doesn't match" in {
    testKeysPruner(testDeployment.copy(name="differentName")) shouldBe None
    testKeysPruner(testDeployment.copy(actions=NEL.of("differentAction"))) shouldBe None
    testKeysPruner(testDeployment.copy(stacks=NEL.of("differentStack"))) shouldBe None
    testKeysPruner(testDeployment.copy(regions=NEL.of("differentRegion"))) shouldBe None
  }

  it should "return the unmodified deployment when it matches" in {
    testKeysPruner(testDeployment) shouldBe Some(testDeployment)
  }

  it should "return a subset of actions, regions and stacks when multiple are specified" in {
    testKeysPruner(testDeployment.copy(
      actions = NEL.of("testAction1", "testAction"),
      stacks = NEL.of("testStack1", "testStack", "testStack2"),
      regions = NEL.of("testRegion1", "testRegion", "testRegion2")
    )) shouldBe Some(testDeployment)
  }

  val testMultipleKeysPruner = Keys(List(
    DeploymentKey("testName", "testAction", "testStack", "testRegion"),
    DeploymentKey("testName", "testAction1", "testStack1", "testRegion1"),
    DeploymentKey("testName100", "testAction", "testStack2", "testRegion2"),
    DeploymentKey("testName", "bobbins", "bobbins", "bobbins")
  ))

  it should "return a subset of actions, regions and stacks that match multiple deployment keys" in {
    val testComplexDeployment = testDeployment.copy(
      actions = NEL.of("testAction1", "testAction"),
      stacks = NEL.of("testStack1", "testStack", "testStack2"),
      regions = NEL.of("testRegion1", "testRegion", "testRegion2")
    )
    testMultipleKeysPruner(testComplexDeployment) shouldBe Some(testDeployment.copy(
      actions = NEL.of("testAction1", "testAction"),
      stacks = NEL.of("testStack1", "testStack"),
      regions = NEL.of("testRegion1", "testRegion")
    ))
  }
}
