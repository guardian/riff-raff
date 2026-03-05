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

  def generate(deploymentTypes: Seq[DeploymentType]): String = {
    val typeNames = deploymentTypes.map(_.name).sorted
    val allParamNames =
      deploymentTypes.flatMap(_.params.map(_.name)).distinct.sorted

    val deploymentOrTemplateSchema =
      caseClassSchema[DeploymentOrTemplate](
        enrichments = Map(
          "type" -> Json.obj(
            "enum" -> JsArray(typeNames.map(JsString))
          ),
          "parameters" -> Json.obj(
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

    val topLevelSchema = caseClassSchema[RiffRaffDeployConfig](
      enrichments = Map(
        "templates" -> Json.obj(
          "additionalProperties" -> deploymentOrTemplateSchema
        ),
        "deployments" -> Json.obj(
          "additionalProperties" -> deploymentOrTemplateSchema
        )
      )
    )

    val schema = Json.obj(
      "$schema" -> "http://json-schema.org/draft-07/schema#",
      "$id" -> "https://github.com/guardian/riff-raff/releases/download/schema-latest/riff-raff-yaml-schema.json",
      "title" -> "Riff-Raff deployment configuration",
      "description" -> "Schema for riff-raff.yaml deployment configuration files. Auto-generated from deployment type definitions."
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
}
