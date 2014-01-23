package resources

import org.joda.time.{DateTime, Duration}
import conf.Configuration
import magenta.Lookup
import java.net.URLEncoder

object `package` {
  implicit class LookupWithStale(lookup: Lookup) {
    def stale: Boolean = {
      new Duration(lookup.lastUpdated, new DateTime).getStandardMinutes > Configuration.lookup.staleMinutes
    }
  }
  implicit class String2UrlEncode(string: String) {
    def urlEncode: String = URLEncoder.encode(string, "UTF-8")
  }
}