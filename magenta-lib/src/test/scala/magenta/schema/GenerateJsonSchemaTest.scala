package magenta.schema

import magenta.input.{DeploymentOrTemplate, RiffRaffDeployConfig}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{JsObject, Json}

import scala.io.Source
import scala.reflect.runtime.universe._

class GenerateJsonSchemaTest extends AnyFlatSpec with Matchers {

  val deploymentTypes = GenerateJsonSchema.deploymentTypes
  val schema = GenerateJsonSchema.generate(deploymentTypes)
  val parsed = Json.parse(schema)
  val deploymentOrTemplateSchema =
    parsed \ "definitions" \ "deployment-or-template"

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
    parsed shouldBe a[JsObject]
  }

  it should "be deterministic (same input produces same output)" in {
    val secondRun = GenerateJsonSchema.generate(deploymentTypes)
    secondRun shouldBe schema
  }

  it should "include all deployment type names in the enum" in {
    val typeNames = deploymentTypes.map(_.name).sorted
    val deploymentsSchema =
      (deploymentOrTemplateSchema \ "properties" \ "type" \ "enum")
        .as[List[String]]
    deploymentsSchema shouldBe typeNames
  }

  it should "include all known parameter names" in {
    val allParamNames =
      deploymentTypes.flatMap(_.params.map(_.name)).distinct.sorted
    val parametersSchema =
      (deploymentOrTemplateSchema \ "properties" \ "parameters" \ "properties")
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
      (deploymentOrTemplateSchema \ "properties")
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
    (deploymentOrTemplateSchema \ "additionalProperties")
      .as[Boolean] shouldBe false
  }

  it should "use $ref for templates and deployments" in {
    val templatesRef =
      (parsed \ "properties" \ "templates" \ "additionalProperties" \ "$ref")
        .as[String]
    val deploymentsRef =
      (parsed \ "properties" \ "deployments" \ "additionalProperties" \ "$ref")
        .as[String]
    templatesRef shouldBe "#/definitions/deployment-or-template"
    deploymentsRef shouldBe "#/definitions/deployment-or-template"
  }

  it should "include descriptions on deployment-or-template fields" in {
    val typeDesc =
      (deploymentOrTemplateSchema \ "properties" \ "type" \ "description")
        .as[String]
    typeDesc should not be empty
  }

  it should "set additionalProperties to false for parameters" in {
    (deploymentOrTemplateSchema \ "properties" \ "parameters" \ "additionalProperties")
      .as[Boolean] shouldBe false
  }

  it should "include the oneOf type/template constraint" in {
    val oneOf =
      (deploymentOrTemplateSchema \ "oneOf").as[List[JsObject]]
    oneOf should have size 2
  }

  it should "use an enum for region items" in {
    val regionEnum =
      (deploymentOrTemplateSchema \ "properties" \ "regions" \ "items" \ "enum")
        .as[List[String]]
    regionEnum should contain("eu-west-1")
    regionEnum should contain("us-east-1")
  }

  it should "reject reserved keywords as deployment names" in {
    val reservedNames =
      (parsed \ "properties" \ "deployments" \ "propertyNames" \ "not" \ "enum")
        .as[List[String]]
    reservedNames should contain allOf ("type", "template", "stacks", "regions", "dependencies", "parameters")
  }

  it should "reject reserved keywords as template names" in {
    val reservedNames =
      (parsed \ "properties" \ "templates" \ "propertyNames" \ "not" \ "enum")
        .as[List[String]]
    reservedNames should contain allOf ("type", "template", "stacks", "regions", "dependencies", "parameters")
  }

  private def caseClassFieldNames[T: TypeTag]: Set[String] = {
    val tpe = typeOf[T]
    val constructor = tpe.decl(termNames.CONSTRUCTOR).asMethod
    constructor.paramLists.flatten
      .map(_.name.decodedName.toString)
      .toSet
  }
}
