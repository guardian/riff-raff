package magenta.input

import org.scalatest.{EitherValues, FlatSpec, ShouldMatchers}
import play.api.libs.json.{JsNumber, JsString, JsValue}

class DeploymentResolverTest extends FlatSpec with ShouldMatchers with EitherValues {
  "DeploymentResolver" should "parse a simple deployment with defaults" in {
    val yaml = RiffRaffDeployConfig(None, None, None,
      Map("test" -> deploymentType("testType").withParameters("testParam" -> JsString("testValue")).withStacks("testStack")))
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have (
      'type ("testType"),
      'stacks (List("testStack")),
      'regions (List("eu-west-1")),
      'app ("test"),
      'contentDirectory ("test"),
      'dependencies (Nil),
      'parameters (Map("testParam" -> JsString("testValue")))
    )
  }

  it should "fill in global defaults and regions when not specified in the deployment" in {
    val yaml = RiffRaffDeployConfig(Some(List("stack1", "stack2")), Some(List("oceania-south-1")), None,
      Map("test" -> deploymentType("testType").withParameters("testParam" -> JsString("testValue"))))
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have (
      'type ("testType"),
      'stacks (List("stack1", "stack2")),
      'regions (List("oceania-south-1")),
      'app ("test"),
      'contentDirectory ("test"),
      'dependencies (Nil),
      'parameters (Map("testParam" -> JsString("testValue")))
    )
  }

  it should "override the global defaults when specified in the deployment" in {
    val yaml = RiffRaffDeployConfig(
      Some(List("overriden-stack1")),
      Some(List("oceania-south-1")), None,
      Map("test" -> deploymentType("testType").withParameters("testParam" -> JsString("testValue")).withStacks("testStack").withRegions("eurasia-north-1"))
    )
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have (
      'type ("testType"),
      'stacks (List("testStack")),
      'regions (List("eurasia-north-1")),
      'app ("test"),
      'contentDirectory ("test"),
      'dependencies (Nil),
      'parameters (Map("testParam" -> JsString("testValue")))
    )
  }

  it should "use values from a simple template" in {
    val yaml = RiffRaffDeployConfig(
      None, None,
      Some(Map("testTemplate" -> deploymentType("testType").withParameters("testParam" -> JsString("testValue")).withStacks("testStack"))),
      Map("test" -> deploymentTemplate("testTemplate").withParameters("anotherParam" -> JsNumber(1984)))
    )
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
    val yaml = RiffRaffDeployConfig(
      Some(List("global-stack")), Some(List("global-region")),
      Some(Map("testTemplate" -> deploymentType("testType").withStacks("template-stack").withRegions("template-region"))),
      Map("test" -> deploymentTemplate("testTemplate").withStacks("deployment-stack").withRegions("deployment-region"))
    )
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have(
      'stacks (List("deployment-stack")),
      'regions (List("deployment-region"))
    )
  }

  it should "correctly prioritise stacks and regions from template when not specified in deployment" in {
    val yaml = RiffRaffDeployConfig(
      Some(List("global-stack")), Some(List("global-region")),
      Some(Map("testTemplate" -> deploymentType("testType").withStacks("template-stack").withRegions("template-region"))),
      Map("test" -> deploymentTemplate("testTemplate"))
    )
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have(
      'stacks (List("template-stack")),
      'regions (List("template-region"))
    )
  }

  it should "correctly prioritise stacks and regions from global when not specified in deployment or template" in {
    val yaml = RiffRaffDeployConfig(
      Some(List("global-stack")), Some(List("global-region")),
      Some(Map("testTemplate" -> deploymentType("testType"))),
      Map("test" -> deploymentTemplate("testTemplate"))
    )
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have(
      'stacks (List("global-stack")),
      'regions (List("global-region"))
    )
  }

  it should "resolve nested templates" in {
    val yaml = RiffRaffDeployConfig(
      Some(List("global-stack")), Some(List("global-region")),
      Some(Map(
        "nestedTemplate" -> deploymentType("testType").withStacks("nested-template-stack").withRegions("nested-template-region"),
        "testTemplate" -> deploymentTemplate("nestedTemplate").withRegions("template-region")
      )),
      Map("test" -> deploymentTemplate("testTemplate"))
    )
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have(
      'stacks (List("nested-template-stack")),
      'regions (List("template-region"))
    )
  }

  it should "correctly merge parameters from templates deployments" in {
    val yaml = RiffRaffDeployConfig(
      Some(List("global-stack")), Some(List("global-region")),
      Some(Map(
        "nestedTemplate" -> deploymentType("testType").withParameters(
          "nestedParameter" -> JsNumber(1984),
          "commonParameter" -> JsString("nested"),
          "allParameter" -> JsString("nested"),
          "sandwichParameter" -> JsString("nested")
        ),
        "testTemplate" -> deploymentTemplate("nestedTemplate").withParameters(
          "templateParameter" -> JsNumber(2016),
          "commonParameter" -> JsString("template"),
          "allParameter" -> JsString("template")
        )
      )),
      Map("test" -> deploymentTemplate("testTemplate").withParameters(
        "deploymentParameter" -> JsNumber(1234),
        "allParameter" -> JsString("deployment"),
        "sandwichParameter" -> JsString("deployment")
      ))
    )
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

  it should "not default app and contentDirectory if specified in template" in {
    val yaml = RiffRaffDeployConfig(
      Some(List("global-stack")), Some(List("global-region")),
      Some(Map("testTemplate" -> deploymentType("testType").withApp("templateApp").withContentDirectory("templateContentDirectory"))),
      Map("test" -> deploymentTemplate("testTemplate"))
    )
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.right.value should have(
      'app ("templateApp"),
      'contentDirectory ("templateContentDirectory")
    )
  }

  it should "correctly prioritise dependencies from deployment when specified everywhere" in {
    val yaml = RiffRaffDeployConfig(
      Some(List("global-stack")), None,
      Some(Map(
        "nestedTemplate" -> deploymentType("testType").withDependencies("nested-dep"),
        "testTemplate" -> deploymentTemplate("nestedTemplate").withDependencies("template-dep")
      )),
      Map(
        "nested-dep" -> deploymentType("autoscaling"),
        "template-dep" -> deploymentType("autoscaling"),
        "deployment-dep" -> deploymentType("autoscaling"),
        "test" -> deploymentTemplate("testTemplate").withDependencies("deployment-dep")
      )
    )
    val deployments = assertDeployments(DeploymentResolver.resolve(yaml))
    deployments.size should be (4)
    val deployment = deployments.find(_.name == "test").get
    deployment.dependencies should be(List("deployment-dep"))
  }

  it should "correctly prioritise dependencies from template when not specified in deployment" in {
    val yaml = RiffRaffDeployConfig(
      Some(List("global-stack")), None,
      Some(Map(
        "nestedTemplate" -> deploymentType("testType").withDependencies("nested-dep"),
        "testTemplate" -> deploymentTemplate("nestedTemplate").withDependencies("template-dep")
      )),
      Map(
        "nested-dep" -> deploymentType("autoscaling"),
        "template-dep" -> deploymentType("autoscaling"),
        "test" -> deploymentTemplate("testTemplate")
      )
    )
    val deployments = assertDeployments(DeploymentResolver.resolve(yaml))
    deployments.size should be (3)
    val deployment = deployments.find(_.name == "test").get
    deployment.dependencies should be(List("template-dep"))
  }

  it should "correctly prioritise dependencies from nested template when not specified in deployment or template" in {
    val yaml = RiffRaffDeployConfig(
      Some(List("global-stack")), None,
      Some(Map(
        "nestedTemplate" -> deploymentType("testType").withDependencies("nested-dep"),
        "testTemplate" -> deploymentTemplate("nestedTemplate")
      )),
      Map(
        "nested-dep" -> deploymentType("autoscaling"),
        "test" -> deploymentTemplate("testTemplate")
      )
    )
    val deployments = assertDeployments(DeploymentResolver.resolve(yaml))
    deployments.size should be (2)
    val deployment = deployments.find(_.name == "test").get
    deployment.dependencies should be(List("nested-dep"))
  }

  it should "report an error if a named template doesn't exist" in {
    val yaml = RiffRaffDeployConfig(
      Some(List("global-stack")), None,
      Some(Map(
        "nestedTemplate" -> deploymentType("testType").withDependencies("nested-dep"),
        "testTemplate" -> deploymentTemplate("nestedTemplate")
      )),
      Map("test" -> deploymentTemplate("nonExistentTemplate"))
    )
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.size should be (1)
    deployments.head.left.value should be("test" -> "Template with name nonExistentTemplate does not exist")
  }

  it should "report an error if no stacks are provided" in {
    val yaml = RiffRaffDeployConfig(
      None, None,
      None,
      Map(
        "test" -> deploymentType("autoscaling")
      )
    )
    val deployments = DeploymentResolver.resolve(yaml)
    deployments.head.left.value should be("test" -> "No stacks provided")
  }

  def assertDeployments(maybeDeployments: List[Either[(String, String), Deployment]]): List[Deployment] = {
    maybeDeployments.flatMap{ either =>
      either should matchPattern { case Right(_) => }
      either.right.toOption
    }
  }

  implicit class RichDeploymentOrTemplate(deploymentOrTemplate: DeploymentOrTemplate) {
    def withType(`type`: String) = deploymentOrTemplate.copy(`type` = Some(`type`))
    def withTemplate(template: String) = deploymentOrTemplate.copy(template = Some(template))
    def withStacks(stacks: String*) = deploymentOrTemplate.copy(stacks = Some(stacks.toList))
    def withRegions(regions: String*) = deploymentOrTemplate.copy(regions = Some(regions.toList))
    def withApp(app: String) = deploymentOrTemplate.copy(app = Some(app))
    def withContentDirectory(contentDirectory: String) = deploymentOrTemplate.copy(contentDirectory = Some(contentDirectory))
    def withDependencies(dependencies: String*) = deploymentOrTemplate.copy(dependencies = Some(dependencies.toList))
    def withParameters(parameters: (String, JsValue)*) = deploymentOrTemplate.copy(parameters = Some(parameters.toMap))
  }

  def deploymentType(`type`: String) = DeploymentOrTemplate(Some(`type`), None, None, None, None, None, None, None)
  def deploymentTemplate(name: String) = DeploymentOrTemplate(None, Some(name), None, None, None, None, None, None)
}
