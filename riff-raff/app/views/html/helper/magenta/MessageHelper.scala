package views.html.helper.magenta

import magenta._
import play.twirl.api.Html

object MessageHelper {
  def stateClassName(state: RunState.Value): String = {
    state match {
      case RunState.Running => "state-running"
      case RunState.NotRunning => "state-not-running"
      case RunState.ChildRunning => "state-child-running"
      case RunState.Completed => "state-completed"
      case RunState.Failed => "state-failed"
    }
  }
  def messageClassName(message: Message): String = {
    message.getClass.getName.toLowerCase.replace("magenta.","message-")
  }
  def classNames(reportNode:ReportTree): String = {
    List(stateClassName(reportNode.cascadeState), messageClassName(reportNode.message)).mkString(" ")
  }
  def trim(html:Html): Html = { Html(html.body.trim) }
}
