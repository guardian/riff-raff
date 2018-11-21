package magenta

import java.util.UUID

import enumeratum._
import magenta.metrics.MagentaMetrics
import magenta.tasks.Task
import org.joda.time.DateTime
import play.api.libs.json.{Format, Json}
import rx.lang.scala.{Observable, Subject}

case class ThrowableDetail(name: String, message:String, stackTrace: String, cause: Option[ThrowableDetail] = None)
object ThrowableDetail {
  implicit def formats: Format[ThrowableDetail] = Json.format[ThrowableDetail]

  implicit def Throwable2ThrowableDetail(t:Throwable): ThrowableDetail = ThrowableDetail(t)
  def apply(t:Throwable): ThrowableDetail = {
    ThrowableDetail(t.getClass.getName, Option(t.getMessage).getOrElse(""), t.getStackTrace.mkString("\n"), Option(t.getCause).map(ThrowableDetail(_)))
  }
}

case class TaskDetail(name: String, description:String, verbose:String) {
  def fullDescription = name + " " + description
}
object TaskDetail {
  implicit def formats: Format[TaskDetail] = Json.format[TaskDetail]

  implicit def Task2TaskDetail(t:Task): TaskDetail = TaskDetail(t)
  implicit def TaskList2TaskDetailList(tl:List[Task]): List[TaskDetail] = tl.map(TaskDetail(_))
  def apply(t:Task): TaskDetail = {
    TaskDetail(t.name, t.description, t.verbose)
  }
}

case class DeployReporter(messageStack: List[Message], messageContext: MessageContext,
  previousReporter: Option[DeployReporter] = None, publishMessages: Boolean) {
  def taskContext[T](task: Task)(block: DeployReporter => T): T = {
    DeployReporter.sendContext(this, Message.TaskRun(task))(block)
  }
  def taskList(tasks: List[Task]) { DeployReporter.send(this, Message.TaskList(tasks)) }
  def info(message: String) { DeployReporter.send(this, Message.Info(message)) }
  def infoContext[T](message: String)(block: DeployReporter => T): T = {
    DeployReporter.sendContext(this, Message.Info(message))(block)
  }
  def commandOutput(message: String) { DeployReporter.send(this, Message.CommandOutput(message)) }
  def commandError(message: String) { DeployReporter.send(this, Message.CommandError(message)) }
  def verbose(message: String) { DeployReporter.send(this, Message.Verbose(message)) }
  def warning(message: String) { DeployReporter.send(this, Message.Warning(message)) }
  def fail(message: String, e: Option[Throwable] = None): Nothing = {
    throw DeployReporter.failException(this, message, e)
  }
  def fail(message: String, e: Throwable): Nothing = { fail(message,Some(e)) }
}

object DeployReporter {
  private val messageSubject = Subject[MessageWrapper]()
  val messages: Observable[MessageWrapper] = messageSubject

  private def send(reporter: DeployReporter, message: Message, messageUUID: UUID = UUID.randomUUID()) {
    val stack = MessageStack(message :: reporter.messageStack)
    MagentaMetrics.MessageBrokerMessages.measure {
      messageSubject.onNext(MessageWrapper(reporter.messageContext, messageUUID, stack))
    }
  }

  // get the genesis reporter that will be the root of all others
  def rootReporterFor(uuid: UUID, parameters: DeployParameters, publishMessages: Boolean = true): DeployReporter = {
    DeployReporter(Nil, MessageContext(uuid, parameters, None), publishMessages = publishMessages)
  }

  def startDeployContext(reporter: DeployReporter) = pushContext(Message.Deploy(reporter.messageContext.parameters), reporter)

  // create a new reporter with the given message at the top of the stack - sends the StartContext event
  def pushContext(message: Message, currentReporter: DeployReporter): DeployReporter = {
      val contextUUID = UUID.randomUUID()
      send(currentReporter, ContextMessage.StartContext(message), contextUUID)
      DeployReporter(
        message :: currentReporter.messageStack,
        currentReporter.messageContext.copy(parentId = Some(contextUUID)),
        Some(currentReporter),
        currentReporter.publishMessages
      )
  }

  // finish the current reporter by sending the FinishContext event - returns the previous reporter if it exists
  def finishContext(currentReporter: DeployReporter): Option[DeployReporter] = {
    currentReporter.messageStack.headOption.foreach(msg => send(currentReporter, ContextMessage.FinishContext(msg)))
    currentReporter.previousReporter
  }
  // fail the reporter by sending a FailContext event
  def failContext(currentReporter: DeployReporter): Option[DeployReporter] = {
    currentReporter.messageStack.headOption.foreach(msg => send(currentReporter, ContextMessage.FailContext(msg)))
    currentReporter.previousReporter
  }
  def failContext(currentReporter: DeployReporter, message: String, reason: Throwable) {
    send(currentReporter, Message.Fail(message, reason))
    failContext(currentReporter)
  }

  def withFailureHandling[T](reporter: DeployReporter)(block: DeployReporter => T): T = {
    try {
      try {
        block(reporter)
      } catch {
        case f:FailException =>
          send(reporter, ContextMessage.FailContext(reporter.messageStack.head))
          throw f
        case t:Throwable =>
          // build exception (and send fail message) first
          val message = reporter.messageStack.head
          val exception = failException(reporter, s"Unhandled exception in ${message.text}", t)
          send(reporter, ContextMessage.FailContext(message))
          throw exception
      }
    } catch {
      case f:FailException =>
        throw if (reporter.previousReporter.isEmpty && f.getCause != null) f.getCause else f
    }
  }

  private def sendContext[T](currentReporter: DeployReporter, message: Message)(block: DeployReporter => T): T = {
    val newReporter = pushContext(message, currentReporter)
    withFailureHandling(newReporter) { reporter =>
      val result = block(reporter)
      finishContext(reporter)
      result
    }
  }

  private def failException(reporter: DeployReporter, message: String, e: Option[Throwable] = None): FailException = {
    val exception = e.getOrElse(new RuntimeException(message))
    send(reporter, Message.Fail(message, exception))
    new FailException(message, e.orNull)
  }
  private def failException(reporter: DeployReporter, message: String, e: Throwable): FailException = {
    failException(reporter, message,Some(e))
  }
}

case class MessageContext(deployId: UUID, parameters: DeployParameters, parentId: Option[UUID])
case class MessageWrapper(context: MessageContext, messageId: UUID, stack: MessageStack)

case class MessageStack(messages: List[Message], time:DateTime = new DateTime()) {
  lazy val top = messages.head
  lazy val deployParameters: Option[DeployParameters] = { messages.filter(_.deployParameters.isDefined).lastOption.flatMap(_.deployParameters) }
}

class FailException(val message: String, val throwable: Throwable = null) extends Throwable(message, throwable)

sealed trait ContextMessage extends Message {
  def originalMessage: Message
  override def deployParameters: Option[DeployParameters] = originalMessage.deployParameters
}

case object ContextMessage extends Enum[ContextMessage] with PlayJsonEnum[ContextMessage] {
  case class StartContext(originalMessage: Message) extends ContextMessage { lazy val text = s"Starting ${originalMessage.text}" }
  case class FailContext(originalMessage: Message) extends ContextMessage { lazy val text = s"Failed during ${originalMessage.text}" }
  case class FinishContext(originalMessage: Message) extends ContextMessage { lazy val text = s"Successfully completed ${originalMessage.text}"}

  val values = findValues
}

sealed trait Message extends EnumEntry {
  def text: String
  def deployParameters: Option[DeployParameters] = None
}

case object Message extends Enum[Message] with PlayJsonEnum[Message] {

  sealed trait ContextMessage extends Message {
    def originalMessage: Message
    override def deployParameters: Option[DeployParameters] = originalMessage.deployParameters
  }

  case class Deploy(parameters: DeployParameters) extends Message {
    lazy val text = s"deploy for ${parameters.build.projectName} (build ${parameters.build.id}) to stage ${parameters.stage.name}"
    override lazy val deployParameters = Some(parameters)
  }

  case class TaskList(taskList: List[TaskDetail]) extends Message {
    lazy val text = s"""Tasks for deploy:
                       |${taskList.mkString("\n")}""".stripMargin
  }
  case class TaskRun(task: TaskDetail) extends Message { lazy val text = s"task ${task.fullDescription}" }
  case class Info(text: String) extends Message
  case class CommandOutput(text: String) extends Message
  case class CommandError(text: String) extends Message
  case class Verbose(text: String) extends Message
  case class Fail(text: String, detail: ThrowableDetail) extends Message
  case class Warning(text: String) extends Message

  val values = findValues
}