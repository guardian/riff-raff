package utils

import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import java.util.Locale

/**
  * Date formats to print datetimes in English style and London time.
  */
object DateFormats {

  val Medium = DateTimeFormat.mediumDateTime
    .withLocale(Locale.UK)
    .withZone(DateTimeZone.forID("Europe/London"))

  val Short = DateTimeFormat.shortDateTime
    .withLocale(Locale.UK)
    .withZone(DateTimeZone.forID("Europe/London"))

}
