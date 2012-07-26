package magenta

import java.util.{UUID, Date}
import magenta.tasks.Task
import util.DynamicVariable
import collection.mutable

object MessageBroker {
  private val listeners = mutable.Buffer[MessageSink]()
  def subscribe(sink: MessageSink) { listeners += sink }
  def unsubscribe(sink: MessageSink) { listeners -= sink }

  private val messageStack = new DynamicVariable[List[Message]](Nil)
  private val uuidContext = new DynamicVariable[UUID](null)

  def withUUID[T](uuid:UUID)(block: => T) {
    uuidContext.withValue(uuid){ block }
  }

  def send(message: Message) {
    val stack = MessageStack(message :: messageStack.value)
    listeners foreach(_.message(uuidContext.value, stack))
  }

  def sendContext[T](message: Message)(block: => T) {
    send(StartContext(message))
    try
      messageStack.withValue(message :: messageStack.value) {
        try
          block
        catch {
          case f:FailException => throw f
          case t => fail("Unhandled exception in %s" format message.toString, Some(t))
        }
      }
    catch {
      case f:FailException =>
        val t = if (messageStack.value.size == 0 && f.getCause != null) f.getCause else f
        send(FailContext(message, t))
        throw t
    }
    send(FinishContext(message))
  }

  def deployContext[T](parameters: DeployParameters)(block: => T) {
    if (messageStack.value.size == 0)
      sendContext(Deploy(parameters))(block)
    else if (messageStack.value.last == Deploy(parameters))
      block
    else
      throw new IllegalStateException("Something went wrong as you have just asked to start a deploy context with %s but we already have a context of %s" format (parameters,messageStack.value))
  }

  def deployContext[T](uuid: UUID, parameters: DeployParameters)(block: => T) {
    withUUID(uuid) { deployContext(parameters) { block } }
  }

  def taskContext[T](task: Task)(block: => T) { sendContext(TaskRun(task))(block) }
  def taskList(tasks: List[Task]) { send(TaskList(tasks)) }
  def info(message: String) { send(Info(message)) }
  def infoContext[T](message: String)(block: => T) { sendContext(Info(message))(block) }
  def commandOutput(message: String) { send(CommandOutput(message)) }
  def commandError(message: String) { send(CommandError(message)) }
  def verbose(message: String) { send(Verbose(message)) }
  def fail(message: String, e: Option[Throwable] = None) {
    send(Fail(message, e.getOrElse(new RuntimeException(message))))
    throw new FailException(message, e.getOrElse(null))
  }
  def fail(message: String, e: Throwable) { fail(message,Some(e)) }
}

trait MessageSink {
  def message(uuid: UUID, stack: MessageStack)
}

class MessageSinkFilter(messageSink: MessageSink, filter: MessageStack => Boolean) extends MessageSink {
  def message(uuid: UUID, stack: MessageStack) { if (filter(stack)) messageSink.message(uuid, stack) }
}

case class MessageStack(messages: List[Message]) {
  lazy val top = messages.head
  lazy val deployParameters: Option[DeployParameters] = { messages.filter(_.deployParameters.isDefined).lastOption.flatMap(_.deployParameters) }
}

class FailException(val message: String, val throwable: Throwable = null) extends Throwable(message, throwable)

sealed trait Message {
  def time = new Date()
  def text: String
  def deployParameters: Option[DeployParameters] = None
}

sealed trait ContextMessage extends Message {
  def originalMessage: Message
  override def deployParameters: Option[DeployParameters] = originalMessage.deployParameters
}

case class Deploy(parameters: DeployParameters) extends Message {
  lazy val text = "deploy for %s" format parameters.build.name
  override lazy val deployParameters = Some(parameters)
}

case class TaskList(taskList: List[Task]) extends Message { lazy val text = "Tasks for deploy:\n%s" format taskList.mkString("\n")}
case class TaskRun(task: Task) extends Message { lazy val text = "task %s" format task.fullDescription }
case class Info(text: String) extends Message
case class CommandOutput(text: String) extends Message
case class CommandError(text: String) extends Message
case class Verbose(text: String) extends Message
case class Fail(text: String, exception: Throwable) extends Message

case class StartContext(originalMessage: Message) extends ContextMessage { lazy val text = "Starting %s" format originalMessage.text }
case class FailContext(originalMessage: Message, exception: Throwable) extends ContextMessage { lazy val text = "Failed during %s" format originalMessage.text }
case class FinishContext(originalMessage: Message) extends ContextMessage { lazy val text = "Successfully completed %s" format originalMessage.text}
