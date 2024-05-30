package magenta.input.resolver

import cats.data.{NonEmptyList => NEL}
import magenta.deployment_type.Param
import magenta.fixtures._
import magenta.input.{ConfigError, Deployment}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.JsNumber

class DeploymentTypeResolverTest
    extends AnyFlatSpec
    with Matchers
    with ValidatedValues {
  val deployment = PartiallyResolvedDeployment(
    "bob",
    "stub-package-type",
    NEL.of("stack"),
    NEL.of("eu-west-1"),
    None,
    actions = None,
    "bob",
    "bob",
    Nil,
    Map.empty
  )
  val deploymentTypes = List(stubDeploymentType(Seq("upload", "deploy")))

  "validateDeploymentType" should "fail on invalid deployment type" in {
    val deploymentWithInvalidType = deployment.copy(`type` = "invalidType")
    val configErrors = DeploymentTypeResolver
      .validateDeploymentType(deploymentWithInvalidType, deploymentTypes)
      .invalid
    configErrors.errors.head.context shouldBe "bob"
    configErrors.errors.head.message should include(s"Unknown type invalidType")
  }

  it should "fail if given an invalid action" in {
    val deploymentWithInvalidAction =
      deployment.copy(actions = Some(NEL.of("invalidAction")))
    val configErrors = DeploymentTypeResolver
      .validateDeploymentType(deploymentWithInvalidAction, deploymentTypes)
      .invalid
    configErrors.errors.head.context shouldBe "bob"
    configErrors.errors.head.message should include(
      s"Invalid action invalidAction for type stub-package-type"
    )
  }

  it should "populate the deployment with default actions if no actions are provided" in {
    val validatedDeployment = DeploymentTypeResolver
      .validateDeploymentType(deployment, deploymentTypes)
      .valid
    validatedDeployment.actions shouldBe NEL.of("upload", "deploy")
  }

  it should "use specified actions if they are provided" in {
    val deploymentWithSpecifiedActions =
      deployment.copy(actions = Some(NEL.of("upload")))
    val validatedDeployment = DeploymentTypeResolver
      .validateDeploymentType(deploymentWithSpecifiedActions, deploymentTypes)
      .valid
    validatedDeployment.actions shouldBe NEL.of("upload")
  }

  it should "preserve other fields" in {
    val validatedDeployment = DeploymentTypeResolver
      .validateDeploymentType(deployment, deploymentTypes)
      .valid
    validatedDeployment should have(
      Symbol("name")("bob"),
      Symbol("type")("stub-package-type"),
      Symbol("stacks")(NEL.of("stack")),
      Symbol("regions")(NEL.of("eu-west-1")),
      Symbol("app")("bob"),
      Symbol("contentDirectory")("bob"),
      Symbol("dependencies")(Nil),
      Symbol("parameters")(Map.empty)
    )
  }

  it should "fail if given an invalid parameter" in {
    val deploymentWithParameters =
      deployment.copy(parameters = Map("param1" -> JsNumber(1234)))
    val configErrors = DeploymentTypeResolver
      .validateDeploymentType(deploymentWithParameters, deploymentTypes)
      .invalid
    configErrors.errors.head shouldBe ConfigError(
      "bob",
      "Parameters provided but not used by stub-package-type deployments: param1"
    )
  }

  it should "fail if a parameter with no default is not provided" in {
    val deploymentTypesWithParams = List(
      stubDeploymentType(
        Seq("upload", "deploy"),
        implicit register => List(Param[String]("param1"))
      )
    )
    val configErrors = DeploymentTypeResolver
      .validateDeploymentType(deployment, deploymentTypesWithParams)
      .invalid
    configErrors.errors.head shouldBe ConfigError(
      "bob",
      "Parameters required for stub-package-type deployments not provided: param1"
    )
  }

  it should "succeed if an optional parameter is not provided" in {
    val deploymentTypesWithParams = List(
      stubDeploymentType(
        Seq("upload", "deploy"),
        implicit register => List(Param[String]("param1", optional = true))
      )
    )
    DeploymentTypeResolver
      .validateDeploymentType(deployment, deploymentTypesWithParams)
      .valid
  }

  it should "succeed if a default is provided" in {
    val deploymentTypesWithParams = List(
      stubDeploymentType(
        Seq("upload", "deploy"),
        implicit register =>
          List(
            Param[String]("param1", defaultValue = Some("defaultValue"))
          )
      )
    )
    DeploymentTypeResolver
      .validateDeploymentType(deployment, deploymentTypesWithParams)
      .valid
  }

  it should "succeed if a default from package is provided" in {
    val deploymentTypesWithParams = List(
      stubDeploymentType(
        Seq("upload", "deploy"),
        implicit register =>
          List(
            Param[String](
              "param1",
              defaultValueFromContext = Some((pkg, _) => Right(pkg.name))
            )
          )
      )
    )
    DeploymentTypeResolver
      .validateDeploymentType(deployment, deploymentTypesWithParams)
      .valid
  }
}
