package magenta.input

import magenta.tasks.YamlToJsonConverter
import play.api.libs.json._
import play.api.libs.functional.syntax._


object RiffRaffYamlReader {
  def fromString(yaml: String) = {
    // convert form YAML to JSON
    val jsonString = YamlToJsonConverter.convert(yaml)

    val json = Json.parse(jsonString)
    Json.fromJson[RiffRaffDeployConfig](json) match {
      case JsSuccess(s, _) => s
      case JsError(errors) => throw new RuntimeException(s"Errors parsing YAML: $errors")
    }
  }
}
