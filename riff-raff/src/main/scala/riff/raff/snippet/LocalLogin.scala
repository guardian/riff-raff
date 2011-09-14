package riff.raff
package snippet

import net.liftweb.util.Helpers._
import net.liftweb.http._
import net.liftweb.util._
import net.liftweb.common._
import model._

class LocalLogin {
  lazy val canDoLocalLogin = Props.devMode && (S.hostName == "localhost")

  def render =
    "*" #> (if (canDoLocalLogin) PassThru else ClearNodes) andThen
    "#jump-the-shark" #> SHtml.button("Yeah, go on then", hackLogin _)

  def hackLogin() {
    val user = AdminUserDetails("Friendly", "Developer", "localhost@guardian.co.uk")
    AdminUser(Full(user))
    S.notice("You are now logged in as " + user.display)
  }

}