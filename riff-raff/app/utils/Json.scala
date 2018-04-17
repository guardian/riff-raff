package utils

import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json._
import org.joda.time.DateTime

import scala.util.{Failure, Success, Try}

object Json {
  implicit object DefaultJodaDateWrites extends Writes[org.joda.time.DateTime] {
    def writes(d: org.joda.time.DateTime): JsValue = JsString(ISODateTimeFormat.dateTime.print(d))
  }
  implicit object DefaultJodaDateReads extends Reads[org.joda.time.DateTime] {
    def reads(json: JsValue): JsResult[DateTime] = {
      json match {
        case JsString(dateTimeStr) =>
          val attemptParse = Try(ISODateTimeFormat.dateTime.parseDateTime(dateTimeStr))
          attemptParse match {
            case Success(dateTime) => JsSuccess(dateTime)
            case Failure(t) => JsError(t.getMessage)
          }
        case _ => JsError("DateTime can only be extracted from a JsString")
      }
    }
  }
}
