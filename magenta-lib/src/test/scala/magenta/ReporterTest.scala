package magenta

import org.scalatest.{FlatSpec, Matchers}
import java.util.UUID

import magenta.ContextMessage._

import collection.mutable.ListBuffer
import magenta.Message._

class ReporterTest extends FlatSpec with Matchers {

  def getWrapperBuffer(uuid: UUID): ListBuffer[MessageWrapper] = {
    val wrappers = ListBuffer.empty[MessageWrapper]
    DeployReporter.messages.filter(_.context.deployId == uuid).subscribe(wrappers += _)
    wrappers
  }

  def getRandomReporter = DeployReporter.rootReporterFor(UUID.randomUUID(), parameters)

  val parameters = DeployParameters(Deployer("Tester"), Build("test-app", "test-build"), Stage("TEST"))

  it should "send messagewrappers to message sinks" in {
    val reporter = getRandomReporter
    val wrappers = getWrapperBuffer(reporter.messageContext.deployId)

    reporter.info("this should work")

    wrappers.size should be(1)
    wrappers.head.context should be(reporter.messageContext)
    wrappers.head.stack.messages should be(List(Info("this should work")))
  }

  it should "send deploy start and finish context messages" in {
    val reporter = getRandomReporter
    val wrappers = getWrapperBuffer(reporter.messageContext.deployId)

    val deployLogger = DeployReporter.startDeployContext(reporter)
    deployLogger.info("this should work")
    DeployReporter.finishContext(deployLogger)

    wrappers.size should be(3)
    wrappers(0).context should be(reporter.messageContext)
    wrappers(0).stack.messages should be(List(StartContext(Deploy(parameters))))
    wrappers(1).context should be(MessageContext(reporter.messageContext.deployId, parameters, Some(wrappers(0).messageId)))
    wrappers(1).stack.messages should be(List(Info("this should work"), Deploy(parameters)))
    wrappers(2).stack.messages should be(List(FinishContext(Deploy(parameters)), Deploy(parameters)))
  }
}
