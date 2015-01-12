package magenta

import org.scalatest.{Matchers, FlatSpec}
import java.util.UUID
import collection.mutable.ListBuffer

class LoggingTest extends FlatSpec with Matchers {

  def getWrapperBuffer(uuid: UUID): ListBuffer[MessageWrapper] = {
    val wrappers = ListBuffer.empty[MessageWrapper]
    MessageBroker.messages.filter(_.context.deployId == uuid).subscribe(wrappers += _)
    wrappers
  }

  def getRandomContext = MessageContext(UUID.randomUUID(), parameters, None)

  val parameters = DeployParameters(Deployer("Tester"), Build("test-app", "test-build"), Stage("TEST"))

  "MessageBroker" should "quietly ignore messages sent without a context" in {
    MessageBroker.info("this shouldn't work")
  }

  it should "send messagewrappers to message sinks" in {
    val context = getRandomContext
    val wrappers = getWrapperBuffer(context.deployId)
    val mbc = MessageBrokerContext(List(Deploy(parameters)), context)

    MessageBroker.withMessageBrokerContext(mbc){
      MessageBroker.info("this should work")
    }

    wrappers.size should be(1)
    wrappers.head.context should be(context)
    wrappers.head.stack.messages should be(List(Info("this should work"), Deploy(parameters)))
  }

  it should "automatically send deploy start and finish context messages" in {
    val context = getRandomContext
    val wrappers = getWrapperBuffer(context.deployId)

    MessageBroker.deployContext(context.deployId, context.parameters){
      MessageBroker.info("this should work")
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

    val logContext = MessageBroker.startDeployContext(context.deployId, context.parameters)
    MessageBroker.withMessageBrokerContext(logContext){
      MessageBroker.info("this should work")
    }
    MessageBroker.finishAllContexts(logContext)

    wrappers.size should be(3)
    wrappers(0).context should be(context)
    wrappers(0).stack.messages should be(List(StartContext(Deploy(parameters))))
    wrappers(1).context should be(MessageContext(context.deployId, parameters, Some(wrappers(0).messageId)))
    wrappers(1).stack.messages should be(List(Info("this should work"), Deploy(parameters)))
    wrappers(2).stack.messages should be(List(FinishContext(Deploy(parameters)), Deploy(parameters)))
  }
}
