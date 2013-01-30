package ci

import play.api.libs.ws.WS
import com.ning.http.client.Realm.AuthScheme
import conf.Configuration.teamcity
import play.api.libs.ws.WS.WSRequestHolder

object TeamCityWS {
  case class Auth(user:String, password:String, scheme:AuthScheme=AuthScheme.BASIC)

  val auth = if (teamcity.useAuth) Some(Auth(teamcity.user.get, teamcity.password.get)) else None
  val teamcityURL ="%s/%s" format (teamcity.serverURL, if (auth.isDefined) "httpAuth" else "guestAuth")

  def url(path: String): WSRequestHolder = {
    val url = "%s%s" format (teamcityURL, path)
    auth.map(ui => WS.url(url).withAuth(ui.user, ui.password, ui.scheme)).getOrElse(WS.url(url))
  }
}
