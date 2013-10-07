package utils

import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json.{JsString, JsValue, Writes}

object Json {
  implicit object DefaultJodaDateWrites extends Writes[org.joda.time.DateTime] {
    def writes(d: org.joda.time.DateTime): JsValue = JsString(ISODateTimeFormat.dateTime.print(d))
  }
}
