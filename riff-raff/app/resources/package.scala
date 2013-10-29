package resources

import org.joda.time.{DateTime, Duration}
import conf.Configuration
import magenta.Lookup

object `package` {
  implicit class LookupWithStale(lookup: Lookup) {
    def stale: Boolean = {
      new Duration(lookup.lastUpdated, new DateTime).getStandardMinutes > Configuration.lookup.staleMinutes
    }
  }
}