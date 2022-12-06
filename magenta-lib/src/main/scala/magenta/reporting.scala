package magenta

import java.util.{Locale, UUID}

import enumeratum.EnumEntry.CapitalWords
import enumeratum.{Enum, EnumEntry, PlayJsonEnum}
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, DateTimeZone}
import magenta.Message._
import magenta.ContextMessage._

sealed trait RunState extends EnumEntry {
  val value: Int
}

object RunState
    extends Enum[RunState]
    with PlayJsonEnum[RunState]
    with CapitalWords {

  val values = findValues

  case object NotRunning extends RunState { override val value: Int = 1 }
  case object Completed extends RunState { override val value: Int = 2 }
  case object Running extends RunState { override val value: Int = 3 }
  case object ChildRunning extends RunState { override val value: Int = 4 }
  case object Failed extends RunState { override val value: Int = 5 }

  def mostSignificant(value1: RunState, value2: RunState): RunState =
    if (value1.value > value2.value) value1 else value2
}

object MessageState {
  def apply(message: Message, time: DateTime, id: UUID): MessageState = {
    message match {
      case start: StartContext => StartMessageState(start, time, id)
      case _                   => SimpleMessageState(message, time, id)
    }
  }
  def apply(
      message: StartContext,
      end: ContextMessage,
      time: DateTime,
      id: UUID
  ): MessageState = end match {
    case finish: FinishContext => FinishMessageState(message, finish, time, id)
    case fail: FailContext     => FailMessageState(message, fail, time, id)
    case notValid =>
      throw new IllegalArgumentException(
        s"Provided end message not a valid end: $notValid"
      )
  }
}

trait MessageState {
  val timeOfDayFormatter = DateTimeFormat.mediumTime
    .withLocale(Locale.UK)
    .withZone(DateTimeZone.forID("Europe/London"))
  def time: DateTime
  def timeOfDay = timeOfDayFormatter.print(time)
  def message: Message
  def startContext: StartContext
  def finished: Option[Message]
  def state: RunState
  def isRunning: Boolean = state == RunState.Running
  def messageId: UUID
}

case class SimpleMessageState(message: Message, time: DateTime, messageId: UUID)
    extends MessageState {
  lazy val startContext = null
  lazy val finished = None
  lazy val state = RunState.NotRunning
}

case class StartMessageState(
    startContext: StartContext,
    time: DateTime,
    messageId: UUID
) extends MessageState {
  lazy val message = startContext.originalMessage
  lazy val finished = None
  lazy val state = RunState.Running
}

case class FinishMessageState(
    startContext: StartContext,
    finish: FinishContext,
    time: DateTime,
    messageId: UUID
) extends MessageState {
  lazy val message = startContext.originalMessage
  lazy val finished = Some(finish)
  lazy val state = RunState.Completed
}

case class FailMessageState(
    startContext: StartContext,
    fail: FailContext,
    time: DateTime,
    messageId: UUID
) extends MessageState {
  lazy val message = startContext.originalMessage
  lazy val finished = Some(fail)
  lazy val state = RunState.Failed
}

trait DeployReport {
  def message: Message
  def timeString: Option[String]
  def state: RunState
  def allMessages: Seq[MessageState]
  def children: List[DeployReportTree]
  def isRunning: Boolean

  def hasChildren: Boolean = children.nonEmpty
  def size: Int = allMessages.size

  def failureMessage: Option[Fail] =
    allMessages.map(_.message).collectFirst { case fail: Fail => fail }

  def cascadeState: RunState = {
    children.foldLeft(state) { (acc: RunState, child: DeployReport) =>
      val childState = child.cascadeState match {
        case RunState.Running => RunState.ChildRunning
        case _                => child.cascadeState
      }
      RunState.mostSignificant(acc, childState)
    }
  }
}

object DeployReport {
  private def wrapperToTree(
      node: MessageWrapper,
      all: List[MessageWrapper]
  ): DeployReportTree = {
    val allChildren = all.filter(_.context.parentId.exists(_ == node.messageId))

    val isEndContextMessage = (wrapper: MessageWrapper) =>
      wrapper.stack.top.isInstanceOf[FinishContext] ||
        wrapper.stack.top.isInstanceOf[FailContext]

    val endOption = allChildren
      .filter(isEndContextMessage)
      .map(_.stack.top.asInstanceOf[ContextMessage])
      .headOption
    val children = allChildren.filterNot(isEndContextMessage)

    val messageState = endOption match {
      case Some(end) =>
        MessageState(
          node.stack.top.asInstanceOf[StartContext],
          end,
          node.stack.time,
          node.messageId
        )
      case None => MessageState(node.stack.top, node.stack.time, node.messageId)
    }

    DeployReportTree(messageState, children.map(wrapperToTree(_, all)))
  }

  def apply(list: List[MessageWrapper]): DeployReport = {
    val maybeRoot = list.find(_.context.parentId.isEmpty)
    maybeRoot match {
      case Some(root) => wrapperToTree(root, list)
      case None       => EmptyDeployReport
    }
  }
}

case object EmptyDeployReport extends DeployReport {
  def message = Verbose("Empty log")
  def timeString: Option[String] = None
  def state = RunState.NotRunning
  def allMessages: Seq[MessageState] = Seq.empty
  def children: List[DeployReportTree] = Nil
  def isRunning: Boolean = false
}

case class DeployReportTree(
    messageState: MessageState,
    children: List[DeployReportTree] = Nil
) extends DeployReport {

  val message = messageState.message
  val timeString = Some(messageState.timeOfDay)
  val state = messageState.state

  private val childRunning: Boolean = children.foldLeft(false) {
    _ || _.isRunning
  }
  val isRunning: Boolean = messageState.isRunning || childRunning

  val allMessages: Seq[MessageState] =
    (messageState :: children.flatMap(_.allMessages)).sortBy(_.time.getMillis)

  private def map(block: MessageState => MessageState): DeployReportTree = {
    DeployReportTree(block(messageState), children.map(_.map(block)))
  }

  def render: Seq[String] = {
    render(Nil)
  }
  def render(position: List[Int]): Seq[String] = {
    val messageRender =
      s"${position.reverse.mkString(".")}:$message [${cascadeState.toString}]"
    val childrenRender = children.zipWithIndex.flatMap {
      case (tree: DeployReport, index: Int) =>
        tree.render(index + 1 :: position)
    }
    messageRender :: childrenRender
  }
}
