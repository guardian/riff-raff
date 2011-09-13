package riff.raff.snippet

import net.liftweb._
import common.Box
import http.S
import net.liftweb.util.Helpers._
import xml.NodeSeq

class BootstrapMsgs {
  def render =
    "* *" #> (display(S.errors, "error") ++ display(S.warnings, "warning") ++ display(S.notices, "info"))


  private def display(msgSrc: List[(NodeSeq, Box[String])], cls: String) =
    S.messages(msgSrc).map(e => <div class={"alert-message " + cls}><p>{e}</p></div>)
}