package resources

import magenta.resources.Lookup
import org.joda.time.{DateTime, Duration}
import conf.Configuration

object `package` {
  implicit class LookupWithStale(lookup: Lookup) {
    def stale: Boolean = {
      new Duration(lookup.lastUpdated, new DateTime).getStandardMinutes > Configuration.lookup.staleMinutes
    }
  }
}