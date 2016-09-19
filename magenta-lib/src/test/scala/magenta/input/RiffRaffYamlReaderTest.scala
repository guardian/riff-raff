package magenta.input

import org.scalatest.{FlatSpec, ShouldMatchers}
import play.api.libs.json.{JsArray, JsNumber, JsString, Json}

class RiffRaffYamlReaderTest extends FlatSpec with ShouldMatchers{
  "RiffRaffYamlReader" should "read a minimal file" in {
    val yaml =
      """
        |---
        |stacks: [banana]
        |deployments:
        |  monkey:
        |    type: autoscaling
      """.stripMargin
    val input = RiffRaffYamlReader.fromString(yaml)
    input.stacks.isDefined should be(true)
    input.stacks.get.size should be(1)
    input.stacks.get should be(List("banana"))
    input.deployments.size should be(1)
    input.deployments.head should be("monkey" -> DeploymentOrTemplate(Some("autoscaling"), None, None, None, None, None, None, None))
  }

  it should "parse a more complex yaml example" in {
    val yaml =
      """
        |---
        |stacks: [banana, cabbage]
        |templates:
        |  custom-auto:
        |    type: autoscaling
        |    parameters:
        |      paramString: value1
        |      paramNumber: 2000
        |      paramList: [valueOne, valueTwo]
        |      paramMap:
        |        txt: text/plain
        |        json: application/json
        |deployments:
        |  human:
        |    template: custom-auto
        |    dependencies: [elephant]
        |    stacks: [carrot]
        |    parameters:
        |      paramString: value2
        |  monkey:
        |    type: autoscaling
        |    app: ook
        |    dependencies: [elephant]
        |  elephant:
        |    type: dung
      """.stripMargin
    val input = RiffRaffYamlReader.fromString(yaml)
    input.stacks.isDefined should be(true)
    input.stacks.get.size should be(2)
    input.stacks.get should be(List("banana", "cabbage"))
    input.templates.isDefined should be(true)
    input.templates.get should be(Map("custom-auto" -> DeploymentOrTemplate(Some("autoscaling"),None, None, None, None, None, None, Some(Map(
      "paramString" -> JsString("value1"),
      "paramNumber" -> JsNumber(2000),
      "paramList" -> JsArray(Seq(JsString("valueOne"), JsString("valueTwo"))),
      "paramMap" -> Json.obj("txt" -> "text/plain", "json" -> "application/json")
    )))))
    input.deployments.size should be(3)
    input.deployments should contain("human" ->
      DeploymentOrTemplate(None, Some("custom-auto"), Some(List("carrot")), None, None, None, Some(List("elephant")), Some(Map("paramString" -> JsString("value2")))))
    input.deployments should contain("monkey" ->
      DeploymentOrTemplate(Some("autoscaling"), None, None, None, Some("ook"), None, Some(List("elephant")), None))
    input.deployments should contain("elephant" ->
      DeploymentOrTemplate(Some("dung"), None, None, None, None, None, None, None))
    input.deployments.map(_._1) should be(List("human", "monkey", "elephant"))
  }
}
