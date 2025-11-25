package magenta.input

import cats.data.Validated.{Invalid, Valid}
import cats.data.{Validated, NonEmptyList => NEL}
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import magenta._
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

object RiffRaffYamlReader {
  implicit def readObjectAsList[V](implicit fmtv: Reads[V]) =
    new Reads[List[(String, V)]] {
      // copied from the map implementation in play.api.libs.json.Reads but builds an ordered
      // list instead of an unordered map
      def reads(json: JsValue): JsResult[List[(String, V)]] = json match {
        case JsObject(linkedMap) =>
          type Errors = scala.collection.Seq[
            (JsPath, scala.collection.Seq[JsonValidationError])
          ]
          def locate(e: Errors, key: String) = e.map { case (p, valerr) =>
            (JsPath \ key) ++ p -> valerr
          }

          linkedMap
            .foldLeft(Right(Nil): Either[Errors, List[(String, V)]]) {
              case (acc, (key, value)) =>
                (acc, Json.fromJson[V](value)(fmtv)) match {
                  case (Right(vs), JsSuccess(v, _)) => Right(vs :+ (key -> v))
                  case (Right(_), JsError(e))       => Left(locate(e, key))
                  case (Left(e), _: JsSuccess[_])   => Left(e)
                  case (Left(e1), JsError(e2)) => Left(e1 ++ locate(e2, key))
                }
            }
            .fold(JsError.apply, res => JsSuccess(res))
        case _ =>
          JsError(
            Seq(JsPath() -> Seq(JsonValidationError("error.expected.jsobject")))
          )
      }
    }

  def fromString(
      yaml: String
  ): Validated[ConfigErrors, RiffRaffDeployConfig] = {
    yamlToJson(yaml) match {
      case Success(json) =>
        Json.fromJson[RiffRaffDeployConfig](json) match {
          case JsSuccess(config, _)    => Valid(config)
          case JsError(errors :: tail) =>
            val nelErrors = NEL(errors, tail)
            Invalid(
              ConfigErrors(nelErrors.map { case (path, validationErrors) =>
                val pathName = if (path.path.isEmpty) "YAML" else path.toString
                ConfigError(
                  s"Parsing $pathName",
                  validationErrors.map(ve => ve.message).mkString(", ")
                )
              })
            )
          case JsError(_) => `wtf?`
        }
      case Failure(exception) =>
        val error = ConfigError("Converting YAML to JSON", exception.getMessage)
        Invalid(ConfigErrors(error))
    }
  }

  def yamlToJson(yaml: String): Try[JsValue] = Try {
    val tree = new ObjectMapper(new YAMLFactory()).readTree(yaml)
    val jsonString = new ObjectMapper()
      .writer(new DefaultPrettyPrinter().withoutSpacesInObjectEntries())
      .writeValueAsString(tree)

    Json.parse(jsonString)
  }
}
