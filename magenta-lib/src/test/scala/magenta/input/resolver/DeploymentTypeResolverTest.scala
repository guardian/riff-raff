package magenta.input.resolver

import magenta.fixtures._
import magenta.input.Deployment
import org.scalatest.{EitherValues, FlatSpec, Matchers}

class DeploymentTypeResolverTest extends FlatSpec with Matchers with EitherValues {
  val deployment = Deployment("bob", "stub-package-type", List("stack"), List("eu-west-1"), actions=None, "bob", "bob", Nil, Map.empty)
  val deploymentTypes = List(stubPackageType(Seq("upload", "deploy")))

  "validateDeploymentType" should "fail on invalid deployment type" in {
    val deploymentWithInvalidType = deployment.copy(`type` = "invalidType")
    val configError = DeploymentTypeResolver.validateDeploymentType(deploymentWithInvalidType, deploymentTypes).left.value
    configError.context shouldBe "bob"
    configError.message should include(s"Unknown type invalidType")
  }

  it should "fail if explicitly given empty actions" in {
    val deploymentWithNoActions = deployment.copy(actions = Some(Nil))
    val configError = DeploymentTypeResolver.validateDeploymentType(deploymentWithNoActions, deploymentTypes).left.value
    configError.context shouldBe "bob"
    configError.message should include(s"Either specify at least one action or omit the actions parameter")
  }

  it should "fail if given an invalid action" in {
    val deploymentWithInvalidAction = deployment.copy(actions = Some(List("invalidAction")))
    val configError = DeploymentTypeResolver.validateDeploymentType(deploymentWithInvalidAction, deploymentTypes).left.value
    configError.context shouldBe "bob"
    configError.message should include(s"Invalid action invalidAction for type stub-package-type")
  }

  it should "populate the deployment with default actions if no actions are provided" in {
    val validatedDeployment = DeploymentTypeResolver.validateDeploymentType(deployment, deploymentTypes).right.value
    validatedDeployment.actions shouldBe Some(List("upload", "deploy"))
  }

  it should "use specified actions if they are provided" in {
    val deploymentWithSpecifiedActions = deployment.copy(actions = Some(List("upload")))
    val validatedDeployment = DeploymentTypeResolver.validateDeploymentType(deploymentWithSpecifiedActions, deploymentTypes).right.value
    validatedDeployment.actions shouldBe Some(List("upload"))
  }

  it should "preserve other fields" in {
    val validatedDeployment = DeploymentTypeResolver.validateDeploymentType(deployment, deploymentTypes).right.value
    validatedDeployment should have(
      'name ("bob"),
      'type ("stub-package-type"),
      'stacks (List("stack")),
      'regions (List("eu-west-1")),
      'app ("bob"),
      'contentDirectory ("bob"),
      'dependencies (Nil),
      'parameters (Map.empty)
    )
  }
}
