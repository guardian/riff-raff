package magenta.deployment_type.param_reads

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import cats.instances.list._
import play.api.libs.json.{JsArray, JsError, JsPath, _}

object PatternValue {
  implicit val reads = Json.reads[PatternValue]

  implicit val patternValueReads = new Reads[List[PatternValue]] {
    def reads(json: JsValue) = {
      json match {
        case JsString(default) => JsSuccess(List(PatternValue(".*", default)))
        case JsArray(patternValues) =>
          patternValues.zipWithIndex
            .foldLeft(
              Valid(Nil): Validated[List[
                (JsPath, scala.collection.Seq[JsonValidationError])
              ], List[PatternValue]]
            ) { case (acc, (value, index)) =>
              val validated = Json.fromJson[PatternValue](value) match {
                case JsSuccess(v, _) => Valid(List(v))
                case JsError(e)      =>
                  Invalid(e.toList.map { case (p, valerr) =>
                    (JsPath \ index.toString) ++ p -> valerr
                  })
              }
              acc combine validated
            }
            .fold(JsError.apply, res => JsSuccess(res))
        case other =>
          JsError(
            "Need a string or an array of objects with pattern and value fields"
          )
      }
    }
  }
}

case class PatternValue(pattern: String, value: String) {
  lazy val regex = pattern.r
}
