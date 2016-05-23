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

case class DeployLogger(messageStack: List[Message], messageContext: MessageContext,
  previousLogger: Option[DeployLogger] = None, publishMessages: Boolean) {
  def taskContext[T](task: Task)(block: DeployLogger => T): T = {
    DeployLogger.sendContext(this, TaskRun(task))(block)
  }
  def taskList(tasks: List[Task]) { DeployLogger.send(this, TaskList(tasks)) }
  def info(message: String) { DeployLogger.send(this, Info(message)) }
  def infoContext[T](message: String)(block: DeployLogger => T): T = {
    DeployLogger.sendContext(this, Info(message))(block)
  }
  def commandOutput(message: String) { DeployLogger.send(this, CommandOutput(message)) }
  def commandError(message: String) { DeployLogger.send(this, CommandError(message)) }
  def verbose(message: String) { DeployLogger.send(this, Verbose(message)) }
  def fail(message: String, e: Option[Throwable] = None): Nothing = {
    throw DeployLogger.failException(this, message, e)
  }
  def fail(message: String, e: Throwable): Nothing = { fail(message,Some(e)) }
}

object DeployLogger {
  private val messageSubject = Subject[MessageWrapper]()
  val messages: Observable[MessageWrapper] = messageSubject

  private def send(logger: DeployLogger, message: Message, messageUUID: UUID = UUID.randomUUID()) {
    val stack = MessageStack(message :: logger.messageStack)
    MagentaMetrics.MessageBrokerMessages.measure {
      messageSubject.onNext(MessageWrapper(logger.messageContext, messageUUID, stack))
    }
  }

  // get the genesis context that will be the root of all others
  def rootLoggerFor(uuid: UUID, parameters: DeployParameters, publishMessages: Boolean = true): DeployLogger = {
    DeployLogger(Nil, MessageContext(uuid, parameters, None), publishMessages = publishMessages)
  }

  def startDeployContext(logger: DeployLogger) = pushContext(Deploy(logger.messageContext.parameters), logger)

  // create a new context with the given message at the top of the stack - sends the StartContext event
  def pushContext(message: Message, currentContext: DeployLogger): DeployLogger = {
      val contextUUID = UUID.randomUUID()
      send(currentContext, StartContext(message), contextUUID)
      DeployLogger(
        message :: currentContext.messageStack,
        currentContext.messageContext.copy(parentId = Some(contextUUID)),
        Some(currentContext),
        currentContext.publishMessages
      )
  }

  // finish the current context by sending the FinishContext event - returns the previous context if it exists
  def finishContext(currentContext: DeployLogger): Option[DeployLogger] = {
    currentContext.messageStack.headOption.foreach(msg => send(currentContext, FinishContext(msg)))
    currentContext.previousLogger
  }
  // finish all contexts recursively
  def finishAllContexts(currentContext: DeployLogger) {
    finishContext(currentContext).foreach(finishAllContexts)
  }
  // fail the context by sending a FailContext event
  def failContext(currentContext: DeployLogger): Option[DeployLogger] = {
    currentContext.messageStack.headOption.foreach(msg => send(currentContext, FailContext(msg)))
    currentContext.previousLogger
  }
  // fail all contexts recursively
  def failAllContexts(currentContext: DeployLogger) {
    failContext(currentContext).foreach(failAllContexts)
  }
  def failAllContexts(currentContext: DeployLogger, message: String, reason: Throwable) {
    send(currentContext, Fail(message, reason))
    failAllContexts(currentContext)
  }

  def withContext[T](context: DeployLogger)(block: DeployLogger => T): T = {
    try {
      try {
        block(context)
      } catch {
        case f:FailException =>
          send(context, FailContext(context.messageStack.head))
          throw f
        case t:Throwable =>
          // build exception (and send fail message) first
          val message = context.messageStack.head
          val exception = failException(context, s"Unhandled exception in ${message.text}", t)
          send(context, FailContext(message))
          throw exception
      }
    } catch {
      case f:FailException =>
        throw if (context.previousLogger.isEmpty && f.getCause != null) f.getCause else f
    }
  }

  private def sendContext[T](currentContext: DeployLogger, message: Message)(block: (DeployLogger) => T): T = {
    val newContext = pushContext(message, currentContext)
    withContext(newContext) { newContext =>
      val result = block(newContext)
      finishContext(newContext)
      result
    }
  }

  private def failException(context: DeployLogger, message: String, e: Option[Throwable] = None): FailException = {
    val exception = e.getOrElse(new RuntimeException(message))
    send(context, Fail(message, exception))
    new FailException(message, e.orNull)
  }
  private def failException(context: DeployLogger, message: String, e: Throwable): FailException = {
    failException(context, message,Some(e))
  }
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
  lazy val text = s"deploy for ${parameters.build.projectName} (build ${parameters.build.id}) to stage ${parameters.stage.name}"
  override lazy val deployParameters = Some(parameters)
}

case class TaskList(taskList: List[TaskDetail]) extends Message { lazy val text =
  s"""Tasks for deploy:
     |${taskList.mkString("\n")}""".stripMargin}
case class TaskRun(task: TaskDetail) extends Message { lazy val text = s"task ${task.fullDescription}" }
case class Info(text: String) extends Message
case class CommandOutput(text: String) extends Message
case class CommandError(text: String) extends Message
case class Verbose(text: String) extends Message
case class Fail(text: String, detail: ThrowableDetail) extends Message

case class StartContext(originalMessage: Message) extends ContextMessage { lazy val text = s"Starting ${originalMessage.text}" }
case class FailContext(originalMessage: Message) extends ContextMessage { lazy val text = s"Failed during ${originalMessage.text}" }
case class FinishContext(originalMessage: Message) extends ContextMessage { lazy val text = s"Successfully completed ${originalMessage.text}"}
