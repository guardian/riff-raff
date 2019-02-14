package utils

import java.util.UUID

import magenta.ContextMessage.StartContext
import magenta.Message.Verbose
import magenta.{DeployReportTree, StartMessageState}
import org.scalatest.{FunSuite, Matchers}
import org.joda.time.DateTime
import views.html.helper.magenta.MessageHelper

class MessageHelperTest extends FunSuite with Matchers {
  test("MessageHelper.messageType should return correct message type") {
    val report = DeployReportTree(messageState = StartMessageState(
      startContext = StartContext(Verbose("verbose")),
      time = DateTime.now, messageId = UUID.randomUUID()))

    MessageHelper.messageType(report) should be ("verbose")
  }

}
