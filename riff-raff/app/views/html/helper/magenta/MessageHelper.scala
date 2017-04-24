package views.html.helper.magenta

import magenta._
import play.twirl.api.Html

object MessageHelper {
  def state(reportNode: DeployReport): String = {
    reportNode.cascadeState match {
      case RunState.Running => "state-running"
      case RunState.NotRunning => "state-not-running"
      case RunState.ChildRunning => "state-child-running"
      case RunState.Completed => "state-completed"
      case RunState.Failed => "state-failed"
    }
  }
  def messageType(reportNode: DeployReport): String = {
    reportNode.message.getClass.getName.toLowerCase.replace("magenta.", "")
  }
  def trim(html: Html): Html = { Html(html.body.trim) }
}
