package magenta

import org.scalatest.{Matchers, FlatSpec}
import java.util.UUID
import collection.mutable.ListBuffer

class LoggingTest extends FlatSpec with Matchers {

  def getWrapperBuffer(uuid: UUID): ListBuffer[MessageWrapper] = {
    val wrappers = ListBuffer.empty[MessageWrapper]
    DeployLogger.messages.filter(_.context.deployId == uuid).subscribe(wrappers += _)
    wrappers
  }

  def getRandomContext = MessageContext(UUID.randomUUID(), parameters, None)

  val parameters = DeployParameters(Deployer("Tester"), Build("test-app", "test-build"), Stage("TEST"))

  "MessageBroker" should "quietly ignore messages sent without a context" in {
    DeployLogger.info("this shouldn't work")
  }

  it should "send messagewrappers to message sinks" in {
    val context = getRandomContext
    val wrappers = getWrapperBuffer(context.deployId)
    val mbc = DeployLogger(List(Deploy(parameters)), context)

    DeployLogger.withMessageBrokerContext(mbc){
      DeployLogger.info("this should work")
    }

    wrappers.size should be(1)
    wrappers.head.context should be(context)
    wrappers.head.stack.messages should be(List(Info("this should work"), Deploy(parameters)))
  }

  it should "automatically send deploy start and finish context messages" in {
    val context = getRandomContext
    val wrappers = getWrapperBuffer(context.deployId)

    DeployLogger.deployContext(context.deployId, context.parameters){
      DeployLogger.info("this should work")
    }

    wrappers.size should be(3)
    wrappers(0).context should be(context)
    wrappers(0).stack.messages should be(List(StartContext(Deploy(parameters))))
    wrappers(1).context should be(MessageContext(context.deployId, parameters, Some(wrappers(0).messageId)))
    wrappers(1).stack.messages should be(List(Info("this should work"), Deploy(parameters)))
    wrappers(2).stack.messages should be(List(FinishContext(Deploy(parameters)), Deploy(parameters)))
  }

  it should "allow the contexts to be sent manually" in {
    val context = getRandomContext
    val wrappers = getWrapperBuffer(context.deployId)

    val logContext = DeployLogger.rootLoggerFor(context.deployId, context.parameters)
    DeployLogger.withMessageBrokerContext(logContext){
      DeployLogger.info("this should work")
    }
    DeployLogger.finishAllContexts(logContext)

    wrappers.size should be(3)
    wrappers(0).context should be(context)
    wrappers(0).stack.messages should be(List(StartContext(Deploy(parameters))))
    wrappers(1).context should be(MessageContext(context.deployId, parameters, Some(wrappers(0).messageId)))
    wrappers(1).stack.messages should be(List(Info("this should work"), Deploy(parameters)))
    wrappers(2).stack.messages should be(List(FinishContext(Deploy(parameters)), Deploy(parameters)))
  }
}
