package magenta.schema

import magenta.deployment_type.{AllTypes, BuildTags, DeploymentType}
import magenta.input.{DeploymentOrTemplate, RiffRaffDeployConfig}
import play.api.libs.json._

import scala.reflect.runtime.universe._

/** Generates a JSON Schema (draft-07) from the deployment type definitions and
  * the input model case classes.
  *
  * The schema structure is derived via reflection from:
  *   - [[RiffRaffDeployConfig]] — top-level YAML structure
  *   - [[DeploymentOrTemplate]] — per-deployment/template structure
  *
  * Deployment type metadata (type names, parameter names, action names) is
  * derived from the [[DeploymentType]] instances.
  *
  * This means adding/removing fields in the case classes automatically updates
  * the schema — no manual changes needed here.
  */
object GenerateJsonSchema {

  private object NoopBuildTags extends BuildTags {
    override def get(projectName: String, buildId: String): Map[String, String] =
      Map.empty
  }

  val deploymentTypes: Seq[DeploymentType] =
    AllTypes.allDeploymentTypes(NoopBuildTags)

  private val definitionName = "deployment-or-template"
  private val definitionRef =
    Json.obj("$ref" -> s"#/definitions/$definitionName")

  // No whitespace allowed in stack names
  private val stackItemPattern = Json.obj("pattern" -> "^\\S+$")

  // Known AWS regions — an enum gives better autocomplete and validation than a regex
  private val awsRegions: Seq[String] = Seq(
    "af-south-1",
    "ap-east-1",
    "ap-northeast-1",
    "ap-northeast-2",
    "ap-northeast-3",
    "ap-south-1",
    "ap-south-2",
    "ap-southeast-1",
    "ap-southeast-2",
    "ap-southeast-3",
    "ap-southeast-4",
    "ca-central-1",
    "ca-west-1",
    "eu-central-1",
    "eu-central-2",
    "eu-north-1",
    "eu-south-1",
    "eu-south-2",
    "eu-west-1",
    "eu-west-2",
    "eu-west-3",
    "il-central-1",
    "me-central-1",
    "me-south-1",
    "sa-east-1",
    "us-east-1",
    "us-east-2",
    "us-west-1",
    "us-west-2"
  )
  private val regionItemEnum =
    Json.obj("enum" -> JsArray(awsRegions.map(JsString)))

  def generate(deploymentTypes: Seq[DeploymentType]): String = {
    val typeNames = deploymentTypes.map(_.name).sorted
    val allParamNames =
      deploymentTypes.flatMap(_.params.map(_.name)).distinct.sorted

    val deploymentOrTemplateSchema =
      caseClassSchema[DeploymentOrTemplate](
        enrichments = Map(
          "type" -> Json.obj(
            "description" -> "The type of deployment to perform",
            "enum" -> JsArray(typeNames.map(JsString))
          ),
          "template" -> Json.obj(
            "description" -> "The name of a template to inherit properties from"
          ),
          "stacks" -> Json.obj(
            "description" -> "Stack tags for this deployment; it will be executed once per stack",
            "items" -> stackItemPattern
          ),
          "regions" -> Json.obj(
            "description" -> "Regions in which this deploy will be executed (defaults to eu-west-1)",
            "items" -> regionItemEnum
          ),
          "allowedStages" -> Json.obj(
            "description" -> "Supported stages for this deployment; attempts to deploy to an unlisted stage will fail"
          ),
          "actions" -> Json.obj(
            "description" -> "Override the list of actions to execute for this deployment type"
          ),
          "app" -> Json.obj(
            "description" -> "Override the app tag (defaults to the deployment name)"
          ),
          "contentDirectory" -> Json.obj(
            "description" -> "Override the content directory for build output (defaults to the deployment name)"
          ),
          "dependencies" -> Json.obj(
            "description" -> "Deployments that must complete before this one starts"
          ),
          "parameters" -> Json.obj(
            "description" -> "Parameters for the deployment type",
            "properties" -> JsObject(
              allParamNames.map { name =>
                name -> Json.obj(
                  "description" -> paramDescription(name, deploymentTypes)
                )
              }
            ),
            "additionalProperties" -> false
          )
        ),
        // type/template oneOf constraint: must have one but not both
        extraConstraints = Json.obj(
          "oneOf" -> Json.arr(
            Json.obj(
              "required" -> Json.arr("type"),
              "not" -> Json.obj("required" -> Json.arr("template"))
            ),
            Json.obj(
              "required" -> Json.arr("template"),
              "not" -> Json.obj("required" -> Json.arr("type"))
            )
          )
        )
      )

    // Deployment/template names that would clash with DeploymentOrTemplate
    // field names should be rejected to catch indentation mistakes
    val reservedNames = caseClassFieldNames[DeploymentOrTemplate]
    val propertyNamesConstraint = Json.obj(
      "propertyNames" -> Json.obj(
        "not" -> Json.obj(
          "enum" -> JsArray(reservedNames.map(JsString))
        )
      )
    )

    val topLevelSchema = caseClassSchema[RiffRaffDeployConfig](
      enrichments = Map(
        "stacks" -> Json.obj("items" -> stackItemPattern),
        "regions" -> Json.obj("items" -> regionItemEnum),
        "templates" -> deepMerge(
          Json.obj("additionalProperties" -> definitionRef),
          propertyNamesConstraint
        ),
        "deployments" -> deepMerge(
          Json.obj("additionalProperties" -> definitionRef),
          propertyNamesConstraint
        )
      )
    )

    val schema = Json.obj(
      "$schema" -> "http://json-schema.org/draft-07/schema#",
      "$id" -> "https://github.com/guardian/riff-raff/releases/download/schema-latest/riff-raff-yaml-schema.json",
      "title" -> "Riff-Raff deployment configuration",
      "description" -> "Schema for riff-raff.yaml deployment configuration files. Auto-generated from deployment type definitions.",
      "definitions" -> Json.obj(
        definitionName -> deploymentOrTemplateSchema
      )
    ) ++ topLevelSchema

    Json.prettyPrint(schema) + "\n"
  }

  /** Use Scala reflection to derive a JSON Schema object from a case class.
    *
    * Each constructor parameter becomes a property. The Scala type is mapped to
    * a JSON Schema type. `Option[X]` fields are optional; all others are
    * required.
    *
    * @param enrichments
    *   Extra schema properties to merge into specific field schemas (e.g. to
    *   add an `enum` constraint to the `type` field).
    * @param extraConstraints
    *   Additional top-level constraints (e.g. `oneOf`) to add to the schema
    *   object.
    */
  private def caseClassSchema[T: TypeTag](
      enrichments: Map[String, JsObject] = Map.empty,
      extraConstraints: JsObject = Json.obj()
  ): JsObject = {
    val tpe = typeOf[T]
    val constructor = tpe.decl(termNames.CONSTRUCTOR).asMethod
    val params = constructor.paramLists.flatten

    val properties = params.map { param =>
      val fieldName = param.name.decodedName.toString
      val fieldType = param.typeSignature
      val (jsonSchemaType, isOptional) = scalaTypeToJsonSchema(fieldType)
      val enriched = enrichments.get(fieldName) match {
        case Some(extra) => deepMerge(jsonSchemaType, extra)
        case None        => jsonSchemaType
      }
      (fieldName, isOptional, enriched)
    }

    val requiredFields = properties.collect { case (name, false, _) =>
      name
    }

    val propertiesObj = JsObject(
      properties.map { case (name, _, schema) =>
        name -> (schema: JsValue)
      }
    )

    val base = Json.obj(
      "type" -> "object",
      "properties" -> propertiesObj,
      "additionalProperties" -> false
    )

    val withRequired =
      if (requiredFields.nonEmpty)
        base + ("required" -> JsArray(requiredFields.map(JsString)))
      else base

    deepMerge(withRequired, extraConstraints)
  }

  /** Map a Scala type to a JSON Schema type definition. Returns (schema,
    * isOptional).
    */
  private def scalaTypeToJsonSchema(
      tpe: Type
  ): (JsObject, Boolean) = {
    if (tpe <:< typeOf[Option[Any]]) {
      val inner = tpe.typeArgs.head
      val (schema, _) = scalaTypeToJsonSchema(inner)
      (schema, true)
    } else if (
      tpe <:< typeOf[List[(String, Any)]] || tpe <:< typeOf[
        Map[String, Any]
      ]
    ) {
      // Map[String, X] or List[(String, X)] — rendered as a YAML/JSON object
      (Json.obj("type" -> "object"), false)
    } else if (tpe <:< typeOf[List[Any]] || tpe <:< typeOf[Seq[Any]]) {
      val itemType = tpe.typeArgs.head
      val (itemSchema, _) = scalaTypeToJsonSchema(itemType)
      (
        Json.obj(
          "type" -> "array",
          "items" -> itemSchema,
          "uniqueItems" -> true
        ),
        false
      )
    } else if (tpe =:= typeOf[String]) {
      (Json.obj("type" -> "string"), false)
    } else if (tpe =:= typeOf[Boolean]) {
      (Json.obj("type" -> "boolean"), false)
    } else if (tpe =:= typeOf[Int] || tpe =:= typeOf[Long]) {
      (Json.obj("type" -> "integer"), false)
    } else if (tpe =:= typeOf[Double] || tpe =:= typeOf[Float]) {
      (Json.obj("type" -> "number"), false)
    } else if (tpe <:< typeOf[JsValue]) {
      // JsValue can be anything
      (Json.obj(), false)
    } else {
      // Unknown type — allow anything
      (Json.obj(), false)
    }
  }

  /** Deep-merge two JsObjects. Values in `overlay` take precedence, but nested
    * objects are merged recursively.
    */
  private def deepMerge(base: JsObject, overlay: JsObject): JsObject = {
    val merged = overlay.fields.foldLeft(base.fields.toMap) {
      case (acc, (key, overlayValue)) =>
        val mergedValue = (acc.get(key), overlayValue) match {
          case (Some(baseObj: JsObject), ov: JsObject) =>
            deepMerge(baseObj, ov)
          case _ => overlayValue
        }
        acc + (key -> mergedValue)
    }
    JsObject(merged)
  }

  private def paramDescription(
      paramName: String,
      deploymentTypes: Seq[DeploymentType]
  ): String = {
    val usedBy = deploymentTypes
      .filter(_.params.exists(_.name == paramName))
      .map(_.name)
      .sorted
    s"Used by: ${usedBy.mkString(", ")}"
  }

  /** Extract the field names of a case class via reflection. */
  private def caseClassFieldNames[T: TypeTag]: Seq[String] = {
    val tpe = typeOf[T]
    val constructor = tpe.decl(termNames.CONSTRUCTOR).asMethod
    constructor.paramLists.flatten.map(_.name.decodedName.toString)
  }
}
