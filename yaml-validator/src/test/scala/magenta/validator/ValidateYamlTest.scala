package magenta.validator

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ValidateYamlTest extends AnyFlatSpec with Matchers {

  private def resourcePath(name: String): String =
    getClass.getClassLoader.getResource(name).getPath

  "ValidateYaml" should "return 0 for a valid simple YAML file" in {
    ValidateYaml.validate(resourcePath("valid-simple.yaml")) should be(0)
  }

  it should "return 0 for a valid complex YAML file with dependencies" in {
    ValidateYaml.validate(resourcePath("valid-complex.yaml")) should be(0)
  }

  it should "return 1 for invalid YAML syntax" in {
    ValidateYaml.validate(resourcePath("invalid-yaml.yaml")) should be(1)
  }

  it should "return 1 for an unknown deployment type" in {
    ValidateYaml.validate(
      resourcePath("invalid-deployment-type.yaml")
    ) should be(1)
  }

  it should "return 1 for a missing dependency" in {
    ValidateYaml.validate(
      resourcePath("invalid-dependency.yaml")
    ) should be(1)
  }

  it should "return 1 when stacks are missing from both top-level and deployment" in {
    ValidateYaml.validate(resourcePath("missing-stacks.yaml")) should be(1)
  }

  it should "return 2 for a non-existent file" in {
    ValidateYaml.validate("/nonexistent/path/riff-raff.yaml") should be(2)
  }

  "validateYaml" should "return 0 for valid inline YAML" in {
    val yaml =
      """
        |stacks: [deploy]
        |regions: [eu-west-1]
        |deployments:
        |  my-app:
        |    type: autoscaling
      """.stripMargin
    ValidateYaml.validateYaml(yaml) should be(0)
  }

  it should "return 1 for invalid inline YAML" in {
    val yaml =
      """
        |stacks: [deploy]
        |regions: [eu-west-1]
        |deployments:
        |  my-app:
        |    type: does-not-exist
      """.stripMargin
    ValidateYaml.validateYaml(yaml) should be(1)
  }
}
