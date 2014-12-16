package magenta

import java.util.UUID
import magenta.tasks.Task
import metrics.MagentaMetrics
import scala.util.DynamicVariable
import collection.mutable
import org.joda.time.DateTime
import rx.lang.scala.{Observable, Subject}

case class ThrowableDetail(name: String, message:String, stackTrace: String, cause: Option[ThrowableDetail] = None)
object ThrowableDetail {
  implicit def Throwable2ThrowableDetail(t:Throwable): ThrowableDetail = ThrowableDetail(t)
  def apply(t:Throwable): ThrowableDetail = {
    ThrowableDetail(t.getClass.getName, Option(t.getMessage).getOrElse(""), t.getStackTrace.mkString(""), Option(t.getCause).map(ThrowableDetail(_)))
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

case class MessageBrokerContext(messageStack: List[Message], messageContext: MessageContext, previousContext: Option[MessageBrokerContext] = None)

object MessageBroker {
  private val messageSubject = Subject[MessageWrapper]()
  val messages: Observable[MessageWrapper] = messageSubject

  private val messageStack = new DynamicVariable[List[Message]](Nil)
  private val messageContext = new DynamicVariable[MessageContext](null)

  def deployID = Option(messageContext.value).map(_.deployId)

  def send(message: Message, messageUUID: UUID = UUID.randomUUID()) {
    Option(messageContext.value).foreach { context =>
      val stack = MessageStack(message :: messageStack.value)
      MagentaMetrics.MessageBrokerMessages.measure {
        messageSubject.onNext(MessageWrapper(context, messageUUID, stack))
      }
    }
  }

  def withMessageBrokerContext[T](context: MessageBrokerContext)(block: => T): T = {
    messageContext.withValue(context.messageContext) {
      messageStack.withValue(context.messageStack) {
        block
      }
    }
  }
  def startDeployContext(uuid: UUID, parameters: DeployParameters): MessageBrokerContext = {
    val initialContext = MessageBrokerContext(Nil, MessageContext(uuid, parameters, None))
    pushContext(Deploy(parameters), initialContext)
  }
  def pushContext(message: Message, currentContext: MessageBrokerContext): MessageBrokerContext = {
    withMessageBrokerContext(currentContext) {
      val contextUUID = UUID.randomUUID()
      send(StartContext(message), contextUUID)
      MessageBrokerContext(message :: currentContext.messageStack, messageContext.value.copy(parentId = Some(contextUUID)), Some(currentContext))
    }
  }
  def finishContext(currentContext: MessageBrokerContext): Option[MessageBrokerContext] = {
    withMessageBrokerContext(currentContext) {
      currentContext.messageStack.headOption.foreach(msg => send(FinishContext(msg)))
      currentContext.previousContext
    }
  }
  def finishAllContexts(currentContext: MessageBrokerContext) {
    finishContext(currentContext).foreach(finishAllContexts)
  }
  def failContext(currentContext: MessageBrokerContext): Option[MessageBrokerContext] = {
    withMessageBrokerContext(currentContext) {
      currentContext.messageStack.headOption.foreach(msg => send(FailContext(msg)))
      currentContext.previousContext
    }
  }
  def failAllContexts(currentContext: MessageBrokerContext) {
    def failAllContextsRec(currentContext: MessageBrokerContext) {
      failContext(currentContext).foreach(failAllContextsRec)
    }
    failAllContextsRec(currentContext)
  }
  def failAllContexts(currentContext: MessageBrokerContext, message: String, reason: Throwable) {
    withMessageBrokerContext(currentContext) {
      send(Fail(message, reason))
    }
    failAllContexts(currentContext)
  }


  def withContext[T](context: MessageBrokerContext)(block: => T): T = {
    try {
      withMessageBrokerContext(context) {
        try {
          block
        } catch {
          case f:FailException =>
            send(FailContext(context.messageStack.head))
            throw f
          case t:Throwable =>
            // build exception (and send fail message) first
            val message = context.messageStack.head
            val exception = failException("Unhandled exception in %s" format message.text, t)
            send(FailContext(message))
            throw exception
        }
      }
    } catch {
      case f:FailException =>
        throw if (context.previousContext.isEmpty && f.getCause != null) f.getCause else f
    }
  }

  def sendContext[T](message: Message)(block: => T): T = {
    Option(messageContext.value) match {
      case Some(context) =>
        val contextUUID = UUID.randomUUID()
        send(StartContext(message), contextUUID)
        withContext(MessageBrokerContext(message :: messageStack.value, context.copy(parentId = Some(contextUUID)))) {
          val result = block
          send(FinishContext(message))
          result
        }
      case None =>
        block
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
      messageContext.withValue(newContext) {
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
case class FailContext(originalMessage: Message) extends ContextMessage { lazy val text = "Failed during %s" format originalMessage.text }
case class FinishContext(originalMessage: Message) extends ContextMessage { lazy val text = "Successfully completed %s" format originalMessage.text}
