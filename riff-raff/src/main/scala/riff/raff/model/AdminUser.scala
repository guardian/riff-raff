package riff.raff.model

import net.liftweb.http.SessionVar
import net.liftweb.common._


case class AdminUserDetails(firstname: String, lastname: String, email: String) {
  def display = "%s %s" format (firstname, lastname)
}

object AdminUser extends SessionVar[Box[AdminUserDetails]](Empty)
