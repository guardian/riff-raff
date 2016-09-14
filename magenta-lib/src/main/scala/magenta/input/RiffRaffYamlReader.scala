package magenta.input

import magenta.tasks.YamlToJsonConverter
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class DeploymentOrTemplate(
  `type`: Option[String],
  template: Option[String],
  stacks: Option[List[String]],
  regions: Option[List[String]],
  app: Option[String],
  contentDirectory: Option[String],
  dependencies: Option[List[String]],
  parameters: Option[Map[String, JsValue]]
)
object DeploymentOrTemplate {
  implicit val reads: Reads[DeploymentOrTemplate] = Json.reads
}

case class RiffRaffYaml(
  stacks: Option[List[String]],
  regions: Option[List[String]],
  templates: Option[Map[String, DeploymentOrTemplate]],
  // TODO - we should extract this in order, using JsObject?
  deployments: Map[String, DeploymentOrTemplate]
) {
  def missingDependencies(dependencies: List[String]): List[String] = dependencies.filterNot(deployments.keySet.contains)
}
object RiffRaffYaml {
  implicit val reads: Reads[RiffRaffYaml] = Json.reads
}

object RiffRaffYamlReader {
  def fromString(yaml: String) = {
    // convert form YAML to JSON
    val jsonString = YamlToJsonConverter.convert(yaml)

    val json = Json.parse(jsonString)
    Json.fromJson[RiffRaffYaml](json) match {
      case JsSuccess(s, _) => s
      case JsError(errors) => throw new RuntimeException(s"Errors parsing YAML: $errors")
    }
  }
}