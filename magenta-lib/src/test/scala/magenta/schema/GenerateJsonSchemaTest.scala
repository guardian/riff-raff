package magenta.schema

import magenta.deployment_type.AllTypes
import magenta.input.{DeploymentOrTemplate, RiffRaffDeployConfig}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsObject, Json}

import scala.io.Source
import scala.reflect.runtime.universe._

class GenerateJsonSchemaTest extends AnyFlatSpec with Matchers {

  val deploymentTypes = AllTypes.allDeploymentTypesForSchema
  val schema = GenerateJsonSchema.generate(deploymentTypes)
  val parsed = Json.parse(schema)

  "GenerateJsonSchema" should "match the expected schema snapshot" in {
    val expected = Source
      .fromResource("riff-raff-yaml-schema.expected.json")
      .mkString
    withClue(
      "Schema output has changed. If this is intentional, run `sbt lib/generateSchema` " +
        "and copy contrib/riff-raff-yaml-schema.json to " +
        "magenta-lib/src/test/resources/riff-raff-yaml-schema.expected.json\n"
    ) {
      schema shouldBe expected
    }
  }

  it should "produce valid JSON" in {
    // If Json.parse didn't throw, the output is valid JSON
    parsed shouldBe a[JsObject]
  }

  it should "be deterministic (same input produces same output)" in {
    val secondRun = GenerateJsonSchema.generate(deploymentTypes)
    secondRun shouldBe schema
  }

  it should "include all deployment type names in the enum" in {
    val typeNames = deploymentTypes.map(_.name).sorted
    // The enum appears inside templates and deployments additionalProperties
    val deploymentsSchema =
      (parsed \ "properties" \ "deployments" \ "additionalProperties" \ "properties" \ "type" \ "enum")
        .as[List[String]]
    deploymentsSchema shouldBe typeNames
  }

  it should "include all known parameter names" in {
    val allParamNames =
      deploymentTypes.flatMap(_.params.map(_.name)).distinct.sorted
    val parametersSchema =
      (parsed \ "properties" \ "deployments" \ "additionalProperties" \ "properties" \ "parameters" \ "properties")
        .as[JsObject]
    parametersSchema.keys.toList.sorted shouldBe allParamNames
  }

  it should "have a property for every field in RiffRaffDeployConfig" in {
    val expectedFields = caseClassFieldNames[RiffRaffDeployConfig]
    val actualFields =
      (parsed \ "properties").as[JsObject].keys
    actualFields shouldBe expectedFields
  }

  it should "have a property for every field in DeploymentOrTemplate" in {
    val expectedFields = caseClassFieldNames[DeploymentOrTemplate]
    val deploymentSchema =
      (parsed \ "properties" \ "deployments" \ "additionalProperties" \ "properties")
        .as[JsObject]
    deploymentSchema.keys shouldBe expectedFields
  }

  it should "mark 'deployments' as required at the top level" in {
    val required = (parsed \ "required").as[List[String]]
    required should contain("deployments")
  }

  it should "set additionalProperties to false at the top level" in {
    (parsed \ "additionalProperties").as[Boolean] shouldBe false
  }

  it should "set additionalProperties to false for deployment-or-template" in {
    val additionalProps =
      (parsed \ "properties" \ "deployments" \ "additionalProperties" \ "additionalProperties")
        .as[Boolean]
    additionalProps shouldBe false
  }

  it should "set additionalProperties to false for parameters" in {
    val additionalProps =
      (parsed \ "properties" \ "deployments" \ "additionalProperties" \ "properties" \ "parameters" \ "additionalProperties")
        .as[Boolean]
    additionalProps shouldBe false
  }

  it should "include the oneOf type/template constraint" in {
    val oneOf =
      (parsed \ "properties" \ "deployments" \ "additionalProperties" \ "oneOf")
        .as[List[JsObject]]
    oneOf should have size 2
  }

  private def caseClassFieldNames[T: TypeTag]: Set[String] = {
    val tpe = typeOf[T]
    val constructor = tpe.decl(termNames.CONSTRUCTOR).asMethod
    constructor.paramLists.flatten
      .map(_.name.decodedName.toString)
      .toSet
  }
}
