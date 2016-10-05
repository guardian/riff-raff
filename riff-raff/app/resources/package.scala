package resources

import org.joda.time.{DateTime, Duration}
import conf.Configuration
import magenta.Lookup
import java.net.URLEncoder

object `package` {

  implicit class String2UrlEncode(string: String) {
    def urlEncode: String = URLEncoder.encode(string, "UTF-8")
  }

}
