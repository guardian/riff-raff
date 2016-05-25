package magenta

import org.scalatest.{Matchers, FlatSpec}
import java.util.UUID
import collection.mutable.ListBuffer

class LoggingTest extends FlatSpec with Matchers {

  def getWrapperBuffer(uuid: UUID): ListBuffer[MessageWrapper] = {
    val wrappers = ListBuffer.empty[MessageWrapper]
    DeployReporter.messages.filter(_.context.deployId == uuid).subscribe(wrappers += _)
    wrappers
  }

  def getRandomLogger = DeployReporter.rootReporterFor(UUID.randomUUID(), parameters)

  val parameters = DeployParameters(Deployer("Tester"), Build("test-app", "test-build"), Stage("TEST"))

  it should "send messagewrappers to message sinks" in {
    val logger = getRandomLogger
    val wrappers = getWrapperBuffer(logger.messageContext.deployId)

    logger.info("this should work")

    wrappers.size should be(1)
    wrappers.head.context should be(logger.messageContext)
    wrappers.head.stack.messages should be(List(Info("this should work")))
  }

  it should "send deploy start and finish context messages" in {
    val logger = getRandomLogger
    val wrappers = getWrapperBuffer(logger.messageContext.deployId)

    val deployLogger = DeployReporter.startDeployContext(logger)
    deployLogger.info("this should work")
    DeployReporter.finishContext(deployLogger)

    wrappers.size should be(3)
    wrappers(0).context should be(logger.messageContext)
    wrappers(0).stack.messages should be(List(StartContext(Deploy(parameters))))
    wrappers(1).context should be(MessageContext(logger.messageContext.deployId, parameters, Some(wrappers(0).messageId)))
    wrappers(1).stack.messages should be(List(Info("this should work"), Deploy(parameters)))
    wrappers(2).stack.messages should be(List(FinishContext(Deploy(parameters)), Deploy(parameters)))
  }
}
