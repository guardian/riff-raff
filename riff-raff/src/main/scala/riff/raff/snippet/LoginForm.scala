package riff.raff.snippet

import net.liftweb.http._
import net.liftweb.util.Helpers._
import riff.raff.model.AdminUser
import net.liftweb.common.Full

class LoginForm {
  object email extends RequestVar[String]("")
  object password extends RequestVar[String]("")

  def render = {
    "#email" #> SHtml.textElem(email) &
    "#password" #> SHtml.passwordElem(password) &
    "type=submit" #> SHtml.submitButton(validateAndSave _)
  }

  def validateAndSave = {
    if (password.is == "changeme") {
      val user = AdminUser(email)
      AdminUser.current.set(Full(user))
      S.redirectTo("/", () => S.notice("You are now logged in as " + user.email))
    }
    S.error("Sorry, invalid password. Try 'changeme'.")
  }

}