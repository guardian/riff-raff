package magenta.input.resolver

import cats.data.{NonEmptyList => NEL}
import magenta.fixtures.ValidatedValues
import magenta.input.{ConfigError, Deployment, RiffRaffYamlReader}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsNumber, JsString}

class DeploymentResolverTest
    extends AnyFlatSpec
    with Matchers
    with ValidatedValues {
  "DeploymentResolver" should "parse a simple deployment with defaults" in {
    val yamlString =
      """
        |deployments:
        |  test:
        |    type: testType
        |    parameters:
        |      testParam: testValue
        |    stacks: [testStack]
        |    regions: [eu-west-1]
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = yaml.andThen(DeploymentResolver.resolve).valid
    deployments.size should be(1)
    deployments.head should have(
      Symbol("type")("testType"),
      Symbol("stacks")(NEL.of("testStack")),
      Symbol("regions")(NEL.of("eu-west-1")),
      Symbol("app")("test"),
      Symbol("contentDirectory")("test"),
      Symbol("actions")(None),
      Symbol("dependencies")(Nil),
      Symbol("parameters")(Map("testParam" -> JsString("testValue")))
    )
  }

  it should "fill in global defaults and regions when not specified in the deployment" in {
    val yamlString =
      """
        |stacks: [stack1, stack2]
        |regions: [oceania-south-1]
        |deployments:
        |  test:
        |    type: testType
        |    parameters:
        |      testParam: testValue
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = yaml.andThen(DeploymentResolver.resolve).valid
    deployments.size should be(1)
    deployments.head should have(
      Symbol("type")("testType"),
      Symbol("stacks")(NEL.of("stack1", "stack2")),
      Symbol("regions")(NEL.of("oceania-south-1")),
      Symbol("app")("test"),
      Symbol("contentDirectory")("test"),
      Symbol("actions")(None),
      Symbol("dependencies")(Nil),
      Symbol("parameters")(Map("testParam" -> JsString("testValue")))
    )
  }

  it should "override the global defaults when specified in the deployment" in {
    val yamlString =
      """
        |stacks: [overriden-stack1]
        |regions: [oceania-south-1]
        |deployments:
        |  test:
        |    type: testType
        |    parameters:
        |      testParam: testValue
        |    actions: [deploymentAction]
        |    stacks: [testStack]
        |    regions: [eurasia-north-1]
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = yaml.andThen(DeploymentResolver.resolve).valid
    deployments.size should be(1)
    deployments.head should have(
      Symbol("type")("testType"),
      Symbol("stacks")(NEL.of("testStack")),
      Symbol("regions")(NEL.of("eurasia-north-1")),
      Symbol("app")("test"),
      Symbol("contentDirectory")("test"),
      Symbol("actions")(Some(NEL.of("deploymentAction"))),
      Symbol("dependencies")(Nil),
      Symbol("parameters")(Map("testParam" -> JsString("testValue")))
    )
  }

  it should "use values from a simple template" in {
    val yamlString =
      """
        |regions: ["eu-west-1"]
        |templates:
        |  testTemplate:
        |    type: testType
        |    parameters:
        |      testParam: testValue
        |    stacks: [testStack]
        |deployments:
        |  test:
        |    template: testTemplate
        |    parameters:
        |      anotherParam: 1984
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = yaml.andThen(DeploymentResolver.resolve).valid
    deployments.size should be(1)
    deployments.head should have(
      Symbol("type")("testType"),
      Symbol("stacks")(NEL.of("testStack")),
      Symbol("regions")(NEL.of("eu-west-1")),
      Symbol("app")("test"),
      Symbol("contentDirectory")("test"),
      Symbol("dependencies")(Nil),
      Symbol("parameters")(
        Map(
          "testParam" -> JsString("testValue"),
          "anotherParam" -> JsNumber(1984)
        )
      )
    )
  }

  it should "correctly prioritise stacks and regions from deployment when specified everywhere" in {
    val yamlString =
      """
        |stacks: [global-stack]
        |regions: [global-region]
        |templates:
        |  testTemplate:
        |    type: testType
        |    stacks: [template-stack]
        |    regions: [template-region]
        |deployments:
        |  test:
        |    template: testTemplate
        |    stacks: [deployment-stack]
        |    regions: [deployment-region]
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = yaml.andThen(DeploymentResolver.resolve).valid
    deployments.size should be(1)
    deployments.head should have(
      Symbol("stacks")(NEL.of("deployment-stack")),
      Symbol("regions")(NEL.of("deployment-region"))
    )
  }

  it should "correctly prioritise stacks and regions from template when not specified in deployment" in {
    val yamlString =
      """
        |stacks: [global-stack]
        |regions: [global-region]
        |templates:
        |  testTemplate:
        |    type: testType
        |    stacks: [template-stack]
        |    regions: [template-region]
        |deployments:
        |  test:
        |    template: testTemplate
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = yaml.andThen(DeploymentResolver.resolve).valid
    deployments.size should be(1)
    deployments.head should have(
      Symbol("stacks")(NEL.of("template-stack")),
      Symbol("regions")(NEL.of("template-region"))
    )
  }

  it should "correctly prioritise stacks and regions from global when not specified in deployment or template" in {
    val yamlString =
      """
        |stacks: [global-stack]
        |regions: [global-region]
        |templates:
        |  testTemplate:
        |    type: testType
        |deployments:
        |  test:
        |    template: testTemplate
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = yaml.andThen(DeploymentResolver.resolve).valid
    deployments.size should be(1)
    deployments.head should have(
      Symbol("stacks")(NEL.of("global-stack")),
      Symbol("regions")(NEL.of("global-region"))
    )
  }

  it should "resolve nested templates" in {
    val yamlString =
      """
        |stacks: [global-stack]
        |regions: [global-region]
        |templates:
        |  nestedTemplate:
        |    type: testType
        |    stacks: [nested-template-stack]
        |    regions: [nested-template-region]
        |  testTemplate:
        |    template: nestedTemplate
        |    regions: [template-region]
        |deployments:
        |  test:
        |    template: testTemplate
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = yaml.andThen(DeploymentResolver.resolve).valid
    deployments.size should be(1)
    deployments.head should have(
      Symbol("stacks")(NEL.of("nested-template-stack")),
      Symbol("regions")(NEL.of("template-region"))
    )
  }

  it should "correctly merge parameters from templates deployments" in {
    val yamlString =
      """
        |stacks: [global-stack]
        |regions: [global-region]
        |templates:
        |  nestedTemplate:
        |    type: testType
        |    parameters:
        |      nestedParameter: 1984
        |      commonParameter: nested
        |      allParameter: nested
        |      sandwichParameter: nested
        |  testTemplate:
        |    template: nestedTemplate
        |    parameters:
        |      templateParameter: 2016
        |      commonParameter: template
        |      allParameter: template
        |deployments:
        |  test:
        |    template: testTemplate
        |    parameters:
        |      deploymentParameter: 1234
        |      allParameter: deployment
        |      sandwichParameter: deployment
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = yaml.andThen(DeploymentResolver.resolve).valid
    deployments.size should be(1)
    val deployment = deployments.head
    deployment.parameters.size should be(6)
    deployment.parameters shouldBe Map(
      "nestedParameter" -> JsNumber(1984),
      "templateParameter" -> JsNumber(2016),
      "deploymentParameter" -> JsNumber(1234),
      "commonParameter" -> JsString("template"),
      "allParameter" -> JsString("deployment"),
      "sandwichParameter" -> JsString("deployment")
    )
  }

  it should "not default actions, app and contentDirectory if specified in template" in {
    val yamlString =
      """
        |stacks: [global-stack]
        |regions: [global-region]
        |templates:
        |  testTemplate:
        |    type: testType
        |    app: templateApp
        |    actions: [templateAction]
        |    contentDirectory: templateContentDirectory
        |deployments:
        |  test:
        |    template: testTemplate
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = yaml.andThen(DeploymentResolver.resolve).valid
    deployments.size should be(1)
    deployments.head should have(
      Symbol("app")("templateApp"),
      Symbol("actions")(Some(NEL.of("templateAction"))),
      Symbol("contentDirectory")("templateContentDirectory")
    )
  }

  it should "correctly prioritise dependencies from deployment when specified everywhere" in {
    val yamlString =
      """
        |stacks: [global-stack]
        |regions: [global-region]
        |templates:
        |  nestedTemplate:
        |    type: testType
        |    dependencies: [nested-dep]
        |  testTemplate:
        |    template: nestedTemplate
        |    dependencies: [template-dep]
        |deployments:
        |  nested-dep:
        |    type: autoscaling
        |  template-dep:
        |    type: autoscaling
        |  deployment-dep:
        |    type: autoscaling
        |  test:
        |    template: testTemplate
        |    dependencies: [deployment-dep]
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = yaml.andThen(DeploymentResolver.resolve).valid
    deployments.size should be(4)
    val deployment = deployments.find(_.name == "test").get
    deployment.dependencies should be(List("deployment-dep"))
  }

  it should "correctly prioritise dependencies from template when not specified in deployment" in {
    val yamlString =
      """
        |stacks: [global-stack]
        |regions: [global-region]
        |templates:
        |  nestedTemplate:
        |    type: testType
        |    dependencies: [nested-dep]
        |  testTemplate:
        |    template: nestedTemplate
        |    dependencies: [template-dep]
        |deployments:
        |  nested-dep:
        |    type: autoscaling
        |  template-dep:
        |    type: autoscaling
        |  test:
        |    template: testTemplate
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = yaml.andThen(DeploymentResolver.resolve).valid
    deployments.size should be(3)
    val deployment = deployments.find(_.name == "test").get
    deployment.dependencies should be(List("template-dep"))
  }

  it should "correctly prioritise dependencies from nested template when not specified in deployment or template" in {
    val yamlString =
      """
        |stacks: [global-stack]
        |regions: [global-region]
        |templates:
        |  nestedTemplate:
        |    type: testType
        |    dependencies: [nested-dep]
        |  testTemplate:
        |    template: nestedTemplate
        |deployments:
        |  nested-dep:
        |    type: autoscaling
        |  test:
        |    template: testTemplate
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = yaml.andThen(DeploymentResolver.resolve).valid
    deployments.size should be(2)
    val deployment = deployments.find(_.name == "test").get
    deployment.dependencies should be(List("nested-dep"))
  }

  it should "report an error if a named template doesn't exist" in {
    val yamlString =
      """
        |stacks: [global-stack]
        |regions: [global-region]
        |templates:
        |  nestedTemplate:
        |    type: testType
        |    dependencies: [nested-dep]
        |  testTemplate:
        |    template: nestedTemplate
        |deployments:
        |  test:
        |    template: nonExistentTemplate
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val configErrors = yaml.andThen(DeploymentResolver.resolve).invalid
    configErrors.errors.toList.size should be(1)
    configErrors.errors.head should be(
      ConfigError(
        "test",
        "Template with name nonExistentTemplate does not exist"
      )
    )
  }

  it should "report an error if a named dependency does not exist" in {
    val yamlString =
      """
        |stacks: [global-stack]
        |regions: [global-region]
        |deployments:
        |  test:
        |    type: autoscaling
        |    dependencies: [missing-dep]
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val configErrors = yaml.andThen(DeploymentResolver.resolve).invalid
    configErrors.errors.toList.size should be(1)
    configErrors.errors.head should be(
      ConfigError("test", "Missing deployment dependencies missing-dep")
    )

  }

  it should "report an error if no stacks are provided" in {
    val yamlString =
      """
        |regions: [global-region]
        |deployments:
        |  test:
        |    type: autoscaling
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val configErrors = yaml.andThen(DeploymentResolver.resolve).invalid
    configErrors.errors.head should be(
      ConfigError("test", "No stacks provided")
    )
  }

  it should "report an error if no regions are provided" in {
    val yamlString =
      """
        |stacks: [global-stack]
        |deployments:
        |  test:
        |    type: autoscaling
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val configErrors = yaml.andThen(DeploymentResolver.resolve).invalid
    configErrors.errors.head should be(
      ConfigError("test", "No regions provided")
    )
  }
}
