package magenta.input.resolver

import cats.data.{NonEmptyList => NEL}
import magenta.input.{Deployment, DeploymentId}
import org.scalatest.{FlatSpec, Matchers}

class DeploymentFilterTest extends FlatSpec with Matchers {
  import DeploymentFilter._
  val testDeployment = Deployment("testName", "testType", NEL.of("testStack"), NEL.of("testRegion"),
    Some(List("testAction")), "testName", "testName", Nil, Map.empty)
  val testStackAndRegionFilter = StackAndRegion("testStack", "testRegion")

  "StackAndRegion filter" should "return None when either the region or stack isn't specified" in {
    testStackAndRegionFilter(testDeployment.copy(stacks = NEL.of("differentStack"))) shouldBe None
    testStackAndRegionFilter(testDeployment.copy(regions = NEL.of("differentRegion"))) shouldBe None
    testStackAndRegionFilter(testDeployment.copy(stacks = NEL.of("differentStack"), regions = NEL.of("differentRegion"))) shouldBe None
  }

  it should "return the unmodified deployment when it has only the specified stack and region" in {
    testStackAndRegionFilter(testDeployment) shouldBe Some(testDeployment)
  }

  it should "return a modified deployment containing just the specified region and stack when there are multiple stacks and regions" in {
    testStackAndRegionFilter(testDeployment.copy(
      stacks = NEL.of("testStack1", "testStack", "testStack2"),
      regions = NEL.of("testRegion1", "testRegion", "testRegion2")
    )) shouldBe Some(testDeployment)
  }

  val testIdsFilter = Ids(List(DeploymentId(testDeployment)))

  "Ids filter" should "return None when the name, action, region or stack doesn't match" in {
    testIdsFilter(testDeployment.copy(name="differentName")) shouldBe None
    testIdsFilter(testDeployment.copy(actions=Some(List("differentAction")))) shouldBe None
    testIdsFilter(testDeployment.copy(stacks=NEL.of("differentStack"))) shouldBe None
    testIdsFilter(testDeployment.copy(regions=NEL.of("differentRegion"))) shouldBe None
  }

  it should "return the unmodified deployment when it matches" in {
    testIdsFilter(testDeployment) shouldBe Some(testDeployment)
  }

  it should "return a subset of actions, regions and stacks when multiple are specified" in {
    testIdsFilter(testDeployment.copy(
      actions = Some(List("testAction1", "testAction")),
      stacks = NEL.of("testStack1", "testStack", "testStack2"),
      regions = NEL.of("testRegion1", "testRegion", "testRegion2")
    )) shouldBe Some(testDeployment)
  }

  val testMultipleIdsFilter = Ids(List(
    DeploymentId("testName", "testAction", "testStack", "testRegion"),
    DeploymentId("testName", "testAction1", "testStack1", "testRegion1"),
    DeploymentId("testName100", "testAction", "testStack2", "testRegion2"),
    DeploymentId("testName", "bobbins", "bobbins", "bobbins")
  ))

  it should "return a subset of actions, regions and stacks that match multiple deployment IDs" in {
    val testComplexDeployment = testDeployment.copy(
      actions = Some(List("testAction1", "testAction")),
      stacks = NEL.of("testStack1", "testStack", "testStack2"),
      regions = NEL.of("testRegion1", "testRegion", "testRegion2")
    )
    testMultipleIdsFilter(testComplexDeployment) shouldBe Some(testDeployment.copy(
      actions = Some(List("testAction1", "testAction")),
      stacks = NEL.of("testStack1", "testStack"),
      regions = NEL.of("testRegion1", "testRegion")
    ))
  }
}
