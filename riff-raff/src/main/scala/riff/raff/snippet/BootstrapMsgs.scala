package riff.raff.snippet

import net.liftweb._
import http.S
import net.liftweb.util.Helpers._

class BootstrapMsgs {
  def render =
    ".error *" #> S.errors.map { case (node, msg) => node }

}