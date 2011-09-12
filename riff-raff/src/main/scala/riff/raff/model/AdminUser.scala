package riff.raff.model

import net.liftweb.http.SessionVar
import net.liftweb.common._


case class AdminUser(email: String)

object AdminUser {
  object current extends SessionVar[Box[AdminUser]](Empty)

  def isLoggedIn = current.isDefined
}

