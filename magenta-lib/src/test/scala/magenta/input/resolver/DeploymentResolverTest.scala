package magenta.input.resolver

import magenta.input.{ConfigError, Deployment, RiffRaffYamlReader}
import org.scalatest.{EitherValues, FlatSpec, ShouldMatchers}
import play.api.libs.json.{JsNumber, JsString}

class DeploymentResolverTest extends FlatSpec with ShouldMatchers with EitherValues {
  "DeploymentResolver" should "parse a simple deployment with defaults" in {
    val yamlString =
      """
        |deployments:
        |  test:
        |    type: testType
        |    parameters:
        |      testParam: testValue
        |    stacks: [testStack]
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have (
      'type ("testType"),
      'stacks (List("testStack")),
      'regions (List("eu-west-1")),
      'app ("test"),
      'contentDirectory ("test"),
      'actions (None),
      'dependencies (Nil),
      'parameters (Map("testParam" -> JsString("testValue")))
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
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have (
      'type ("testType"),
      'stacks (List("stack1", "stack2")),
      'regions (List("oceania-south-1")),
      'app ("test"),
      'contentDirectory ("test"),
      'actions (None),
      'dependencies (Nil),
      'parameters (Map("testParam" -> JsString("testValue")))
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
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have (
      'type ("testType"),
      'stacks (List("testStack")),
      'regions (List("eurasia-north-1")),
      'app ("test"),
      'contentDirectory ("test"),
      'actions (Some(List("deploymentAction"))),
      'dependencies (Nil),
      'parameters (Map("testParam" -> JsString("testValue")))
    )
  }

  it should "use values from a simple template" in {
    val yamlString =
      """
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
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have (
      'type ("testType"),
      'stacks (List("testStack")),
      'regions (List("eu-west-1")),
      'app ("test"),
      'contentDirectory ("test"),
      'dependencies (Nil),
      'parameters (Map("testParam" -> JsString("testValue"), "anotherParam" -> JsNumber(1984)))
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
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have(
      'stacks (List("deployment-stack")),
      'regions (List("deployment-region"))
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
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have(
      'stacks (List("template-stack")),
      'regions (List("template-region"))
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
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have(
      'stacks (List("global-stack")),
      'regions (List("global-region"))
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
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have(
      'stacks (List("nested-template-stack")),
      'regions (List("template-region"))
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
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    val deployment = deployments.head.right.value
    deployment.parameters.size should be(6)
    deployment.parameters should contain("nestedParameter" -> JsNumber(1984))
    deployment.parameters should contain("templateParameter" -> JsNumber(2016))
    deployment.parameters should contain("deploymentParameter" -> JsNumber(1234))
    deployment.parameters should contain("commonParameter" -> JsString("template"))
    deployment.parameters should contain("allParameter" -> JsString("deployment"))
    deployment.parameters should contain("sandwichParameter" -> JsString("deployment"))
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
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have(
      'app ("templateApp"),
      'actions (Some(List("templateAction"))),
      'contentDirectory ("templateContentDirectory")
    )
  }

  it should "correctly prioritise dependencies from deployment when specified everywhere" in {
    val yamlString =
      """
        |stacks: [global-stack]
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
    val deployments = assertDeployments(DeploymentResolver.resolve(yaml))
    deployments.size should be (4)
    val deployment = deployments.find(_.name == "test").get
    deployment.dependencies should be(List("deployment-dep"))
  }

  it should "correctly prioritise dependencies from template when not specified in deployment" in {
    val yamlString =
      """
        |stacks: [global-stack]
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
    val deployments = assertDeployments(DeploymentResolver.resolve(yaml))
    deployments.size should be (3)
    val deployment = deployments.find(_.name == "test").get
    deployment.dependencies should be(List("template-dep"))
  }

  it should "correctly prioritise dependencies from nested template when not specified in deployment or template" in {
    val yamlString =
      """
        |stacks: [global-stack]
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
    val deployments = assertDeployments(DeploymentResolver.resolve(yaml))
    deployments.size should be (2)
    val deployment = deployments.find(_.name == "test").get
    deployment.dependencies should be(List("nested-dep"))
  }

  it should "report an error if a named template doesn't exist" in {
    val yamlString =
      """
        |stacks: [global-stack]
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
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.left.value should be(ConfigError("test", "Template with name nonExistentTemplate does not exist"))
  }

  it should "report an error if a named dependency does not exist" in {
    val yamlString =
      """
        |stacks: [global-stack]
        |deployments:
        |  test:
        |    type: autoscaling
        |    dependencies: [missing-dep]
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.left.value should be(ConfigError("test", "Missing deployment dependencies missing-dep"))

  }

  it should "report an error if no stacks are provided" in {
    val yamlString =
      """
        |deployments:
        |  test:
        |    type: autoscaling
      """.stripMargin
    val yaml = RiffRaffYamlReader.fromString(yamlString)
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.head.left.value should be(ConfigError("test", "No stacks provided"))
  }

  def assertDeployments(maybeDeployments: List[Either[ConfigError, Deployment]]): List[Deployment] = {
    maybeDeployments.flatMap{ either =>
      either should matchPattern { case Right(_) => }
      either.right.toOption
    }
  }
}
