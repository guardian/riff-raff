package magenta

import java.util.UUID
import magenta.tasks.Task
import metrics.MagentaMetrics
import util.DynamicVariable
import collection.mutable
import org.joda.time.DateTime

case class ThrowableDetail(name: String, message:String, stackTrace: String, cause: Option[ThrowableDetail] = None)
object ThrowableDetail {
  implicit def Throwable2ThrowableDetail(t:Throwable): ThrowableDetail = ThrowableDetail(t)
  def apply(t:Throwable): ThrowableDetail = {
    ThrowableDetail(t.getClass.getName, Option(t.getMessage).getOrElse(""), t.getStackTraceString, Option(t.getCause).map(ThrowableDetail(_)))
  }
}

case class TaskDetail(name: String, description:String, verbose:String, taskHosts: List[Host]) {
  def fullDescription = name + " " + description
}
object TaskDetail {
  implicit def Task2TaskDetail(t:Task): TaskDetail = TaskDetail(t)
  implicit def TaskList2TaskDetailList(tl:List[Task]): List[TaskDetail] = tl.map(TaskDetail(_)).toList
  def apply(t:Task): TaskDetail = {
    TaskDetail(t.name, t.description, t.verbose, t.taskHost.toList)
  }
}

object MessageBroker {
  private val listeners = mutable.Buffer[MessageSink]()
  def subscribe(sink: MessageSink) { listeners += sink }
  def unsubscribe(sink: MessageSink) { listeners -= sink }

  private val messageStack = new DynamicVariable[List[Message]](Nil)
  private val messageContext = new DynamicVariable[MessageContext](null)

  def deployID = Option(messageContext.value).map(_.deployId)

  def send(message: Message, messageUUID: UUID = UUID.randomUUID()) {
    val stack = MessageStack(message :: messageStack.value)
    MagentaMetrics.MessageBrokerMessages.measure {
      listeners foreach(_.message(MessageWrapper(messageContext.value, messageUUID, stack)))
    }
  }

  def sendContext[T](message: Message)(block: => T): T = {
    val contextUUID = UUID.randomUUID()
    send(StartContext(message), contextUUID)
    try {
      messageContext.withValue(messageContext.value.copy(parentId = Some(contextUUID))) {
        messageStack.withValue(message :: messageStack.value) {
          try {
            val result: T = block
            send(FinishContext(message))
            result
          } catch {
            case f:FailException =>
              send(FailContext(message, f))
              throw f
            case t =>
              // build exception (and send fail message) first
              val exception = failException("Unhandled exception in %s" format message.text, t)
              send(FailContext(message, t))
              throw exception
          }
        }
      }
    } catch {
      case f:FailException =>
        throw if (messageContext.value.parentId.isEmpty && f.getCause != null) f.getCause else f
    }
  }

  def deployContext[T](uuid: UUID, parameters: DeployParameters)(block: => T): T = {
    val newContext = MessageContext(uuid, parameters, None)

    val contextDefined = Option(messageContext.value).isDefined
    val reentrant = contextDefined &&
                    messageContext.value.deployId == uuid &&
                    messageContext.value.parameters == parameters

    if (contextDefined && !reentrant)
      throw new IllegalStateException("Something went wrong as you have just asked to start a deploy context with %s but we already have a context of %s" format (newContext, messageContext.value))

    if (reentrant) {
      block
    } else {
      messageContext.withValue(MessageContext(uuid, parameters, None)) {
        sendContext(Deploy(parameters))(block)
      }
    }
  }

  def taskContext[T](task: Task)(block: => T) { sendContext(TaskRun(task))(block) }
  def taskList(tasks: List[Task]) { send(TaskList(tasks)) }
  def info(message: String) { send(Info(message)) }
  def infoContext[T](message: String)(block: => T) { sendContext(Info(message))(block) }
  def commandOutput(message: String) { send(CommandOutput(message)) }
  def commandError(message: String) { send(CommandError(message)) }
  def verbose(message: String) { send(Verbose(message)) }
  def failException(message: String, e: Option[Throwable] = None): FailException = {
    val exception = e.getOrElse(new RuntimeException(message))
    send(Fail(message, exception))
    new FailException(message, e.getOrElse(null))
  }
  def fail(message: String, e: Option[Throwable] = None): Nothing = {
    throw failException(message, e)
  }
  def failException(message: String, e: Throwable): FailException = { failException(message,Some(e)) }
  def fail(message: String, e: Throwable): Nothing = { fail(message,Some(e)) }
}

case class MessageContext(deployId: UUID, parameters: DeployParameters, parentId: Option[UUID])
case class MessageWrapper(context: MessageContext, messageId: UUID, stack: MessageStack)

trait MessageSink {
  def message(wrapper: MessageWrapper)
}

class MessageSinkFilter(messageSink: MessageSink, filter: MessageStack => Boolean) extends MessageSink {
  def message(wrapper: MessageWrapper) {
    if (filter(wrapper.stack))
      messageSink.message(wrapper)
  }
}

case class MessageStack(messages: List[Message], time:DateTime = new DateTime()) {
  lazy val top = messages.head
  lazy val deployParameters: Option[DeployParameters] = { messages.filter(_.deployParameters.isDefined).lastOption.flatMap(_.deployParameters) }
}

class FailException(val message: String, val throwable: Throwable = null) extends Throwable(message, throwable)

trait Message {
  def text: String
  def deployParameters: Option[DeployParameters] = None
}

trait ContextMessage extends Message {
  def originalMessage: Message
  override def deployParameters: Option[DeployParameters] = originalMessage.deployParameters
}

case class Deploy(parameters: DeployParameters) extends Message {
  lazy val text = "deploy for %s (build %s) to stage %s" format (parameters.build.projectName, parameters.build.id, parameters.stage.name)
  override lazy val deployParameters = Some(parameters)
}

case class TaskList(taskList: List[TaskDetail]) extends Message { lazy val text = "Tasks for deploy:\n%s" format taskList.mkString("\n")}
case class TaskRun(task: TaskDetail) extends Message { lazy val text = "task %s" format task.fullDescription }
case class Info(text: String) extends Message
case class CommandOutput(text: String) extends Message
case class CommandError(text: String) extends Message
case class Verbose(text: String) extends Message
case class Fail(text: String, detail: ThrowableDetail) extends Message

case class StartContext(originalMessage: Message) extends ContextMessage { lazy val text = "Starting %s" format originalMessage.text }
case class FailContext(originalMessage: Message, detail: ThrowableDetail) extends ContextMessage { lazy val text = "Failed during %s" format originalMessage.text }
case class FinishContext(originalMessage: Message) extends ContextMessage { lazy val text = "Successfully completed %s" format originalMessage.text}
