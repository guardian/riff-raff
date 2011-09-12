package riff.raff.snippet

import net.liftweb.http._
import net.liftweb.util.Helpers._

class LoginForm {
  object email extends RequestVar[String]("")
  object password extends RequestVar[String]("")

  def render = {
    "#email" #> SHtml.textElem(email) &
    "#password" #> SHtml.passwordElem(password) &
    "type=submit" #> SHtml.submitButton(validateAndSave _)
  }

  def validateAndSave = {
    println("logn?")
    S.error("sorry not implemented yet, but your email was " + email.is + " and " + password.is)
    S.error("another message")
//    newUser.validate match {
//      case Nil => newUser.save; S.notice("Saved")
//      case errors => S.error(errors)
//    }
  }

}