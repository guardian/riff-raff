package riff.raff.snippet

import net.liftweb._
import http.S
import net.liftweb.util.Helpers._

class Stage {
  def name = "* *" #> S.param("stage")

}