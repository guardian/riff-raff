package magenta.deployment_type.param_reads

import play.api.data.validation.ValidationError
import play.api.libs.json.{JsArray, JsError, JsPath, _}

object PatternValue {
  implicit val reads = Json.reads[PatternValue]

  implicit val patternValueReads = new Reads[List[PatternValue]] {
    def reads(json: JsValue) = {
      json match {
        case JsString(default) => JsSuccess(List(PatternValue(".*", default)))
        case JsArray(patternValues) =>
          type Errors = Seq[(JsPath, Seq[ValidationError])]
          def locate(e: Errors, key: String) = e.map { case (p, valerr) => (JsPath \ key) ++ p -> valerr }

          patternValues.zipWithIndex
            .foldLeft(Right(Nil): Either[Errors, List[PatternValue]]) {
              case (acc, (value, index)) =>
                (acc, Json.fromJson[PatternValue](value)) match {
                  case (Right(vs), JsSuccess(v, _)) => Right(vs :+ v)
                  case (Right(_), JsError(e)) => Left(locate(e, index.toString))
                  case (Left(e), _: JsSuccess[_]) => Left(e)
                  case (Left(e1), JsError(e2)) => Left(e1 ++ locate(e2, index.toString))
                }
            }
            .fold(JsError.apply, res => JsSuccess(res))
        case other => JsError("Need a string or an array of objects with pattern and value fields")
      }
    }
  }
}

case class PatternValue(pattern: String, value: String) {
  lazy val regex = pattern.r
}
