package magenta

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import java.util.UUID

object RunState extends Enumeration {
  type State = Value
  val NotRunning = Value("Not running")
  val Completed = Value("Completed")
  val Running = Value("Running")
  val ChildRunning = Value("Child running")
  val Failed = Value("Failed")

  def mostSignificant(value1: Value, value2: Value): Value = {
    if (value1.id > value2.id) value1 else value2
  }
}

object MessageState {
  def apply(message: Message, time:DateTime): MessageState = {
    message match {
      case start:StartContext => StartMessageState(start, time)
      case _ => SimpleMessageState(message, time)
    }
  }
  def apply(message: StartContext, end: ContextMessage, time:DateTime): MessageState = {
    end match {
      case finish:FinishContext => FinishMessageState(message, finish, time)
      case fail:FailContext => FailMessageState(message, fail, time)
    }
  }
}

trait MessageState {
  val timeOfDayFormatter=DateTimeFormat.mediumTime()
  def time:DateTime
  def timeOfDay() = timeOfDayFormatter.print(time)
  def message:Message
  def startContext:StartContext
  def finished:Option[Message]
  def state: RunState.State
  def isRunning:Boolean = state == RunState.Running
  def messageId: Option[UUID]
}

case class Report(text: String, time: DateTime, messageId: Option[UUID] = None) extends MessageState {
  lazy val message = Info(text)
  lazy val startContext = null
  lazy val finished = None
  lazy val state = RunState.NotRunning
}

case class SimpleMessageState(message: Message, time: DateTime, messageId: Option[UUID] = None) extends MessageState {
  lazy val startContext = null
  lazy val finished = None
  lazy val state = RunState.NotRunning
}

case class StartMessageState(startContext: StartContext, time: DateTime, messageId: Option[UUID] = None) extends MessageState {
  lazy val message = startContext.originalMessage
  lazy val finished = None
  lazy val state = RunState.Running
}

case class FinishMessageState(startContext: StartContext, finish: FinishContext, time: DateTime, messageId: Option[UUID] = None) extends MessageState {
  lazy val message = startContext.originalMessage
  lazy val finished = Some(finish)
  lazy val state = RunState.Completed
}

case class FailMessageState(startContext: StartContext, fail: FailContext, time: DateTime, messageId: Option[UUID] = None) extends MessageState {
  lazy val message = startContext.originalMessage
  lazy val finished = Some(fail)
  lazy val state = RunState.Failed
}

object DeployReport {
  def wrapperToTree(node: MessageWrapper, all: List[MessageWrapper]): ReportTree = {
    val allChildren = all.filter(_.context.parentId.exists(_ == node.messageId))

    val isEndContextMessage = (wrapper:MessageWrapper) => wrapper.stack.top.isInstanceOf[FinishContext] ||
                                                          wrapper.stack.top.isInstanceOf[FailContext]

    val endOption = allChildren.filter(isEndContextMessage).map(_.stack.top.asInstanceOf[ContextMessage]).headOption
    val children = allChildren.filterNot(isEndContextMessage)

    val messageState = endOption.map { end =>
      MessageState(node.stack.top.asInstanceOf[StartContext], end, node.stack.time)
    }.getOrElse(MessageState(node.stack.top, node.stack.time))

    ReportTree(messageState, children.map(wrapperToTree(_,all)))
  }

  def apply(list: List[MessageWrapper], title: String = "", titleTime: Option[DateTime] = None): ReportTree = {
    val time = titleTime.getOrElse( list.headOption.map(_.stack.time).getOrElse( new DateTime() ))
    ReportTree(Report(title, time), list.headOption.map(root => List(wrapperToTree(root,list))).getOrElse(Nil))
  }
}

case class ReportTree(messageState: MessageState, children: List[ReportTree] = Nil) {

  lazy val message = messageState.message
  lazy val finished = messageState.finished
  lazy val state = messageState.state

  lazy val cascadeState: RunState.Value = {
    children.foldLeft(state){ (acc:RunState.Value, child:ReportTree) =>
      val childState = child.cascadeState match {
        case RunState.Running => RunState.ChildRunning
        case _ => child.cascadeState
      }
      RunState.mostSignificant(acc,childState)
    }
  }

  lazy val isRunning: Boolean = messageState.isRunning || childRunning
  lazy val childRunning: Boolean = children.foldLeft(false){_ || _.isRunning}

  lazy val failureMessage: Option[Fail] = {
    allMessages.filter(_.message.getClass == classOf[Fail]).map(_.message).headOption.asInstanceOf[Option[Fail]]
  }

  lazy val startTime = allMessages.head.time

  lazy val allMessages: Seq[MessageState] = (messageState :: children.flatMap(_.allMessages)).sortWith{ (left, right) =>
    left.time.getMillis < right.time.getMillis
  }

  lazy val tasks = {
    allMessages flatMap {
      _.message match {
        case taskList:TaskList => taskList.taskList
        case _ => Nil
      }
    }
  }

  lazy val hostNames = tasks.flatMap(_.taskHosts).map(_.name).distinct

  def appendChild(newChild: MessageState): ReportTree = {
    ReportTree(messageState, children ::: List(ReportTree(newChild)))
  }

  def appendChild(stack: List[Message], time: DateTime): ReportTree = {
    if (stack.size == 1) {
      appendChild(MessageState(stack.head, time))
    } else {
      ReportTree(messageState, children map { child =>
        if (child.message != stack.head)
          child
        else
          child.appendChild(stack.tail, time)
      })
    }
  }

  def map(block: MessageState => MessageState): ReportTree = {
    ReportTree(block(messageState), children.map(_.map(block)))
  }

  def contains(thisMessage: Message): Boolean = allMessages.contains(thisMessage)

  def render: Seq[String] = {
    render(Nil)
  }
  def render(position: List[Int]): Seq[String] = {
    val messageRender = s"${position.reverse.mkString(".")}:$message [${cascadeState.toString}]"
    val childrenRender = children.zipWithIndex.flatMap{ case (tree: ReportTree, index: Int) => tree.render(index+1 :: position) }
    messageRender :: childrenRender
  }

  lazy val size: Int = allMessages.size
}