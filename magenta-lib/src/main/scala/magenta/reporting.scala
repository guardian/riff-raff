package magenta

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
  def apply(message: Message): MessageState = {
    message match {
      case start:StartContext => StartMessageState(start)
      case _ => SimpleMessageState(message)
    }
  }
  def apply(message: StartContext, finish: FinishContext): MessageState = FinishMessageState(message, finish)
  def apply(message: StartContext, fail: FailContext): MessageState = FailMessageState(message, fail)
}

trait MessageState {
  def message:Message
  def startContext:StartContext
  def finished:Option[Message]
  def state: RunState.State
  def isRunning:Boolean = state == RunState.Running
}

case class Report(text: String) extends MessageState {
  lazy val message = Info(text)
  lazy val startContext = null
  lazy val finished = None
  lazy val state = RunState.NotRunning
}

case class SimpleMessageState(message: Message) extends MessageState {
  lazy val startContext = null
  lazy val finished = None
  lazy val state = RunState.NotRunning
}

case class StartMessageState(startContext: StartContext) extends MessageState {
  lazy val message = startContext.originalMessage
  lazy val finished = None
  lazy val state = RunState.Running
}

case class FinishMessageState(startContext: StartContext, finish: FinishContext) extends MessageState {
  lazy val message = startContext.originalMessage
  lazy val finished = Some(finish)
  lazy val state = RunState.Completed
}

case class FailMessageState(startContext: StartContext, fail: FailContext) extends MessageState {
  lazy val message = startContext.originalMessage
  lazy val finished = Some(fail)
  lazy val state = RunState.Failed
}

object DeployReport {
  def apply(messageList: List[MessageStack], title: String = ""): ReportTree = {
    messageList.foldLeft(ReportTree(Report(title))){ (acc:ReportTree,stack:MessageStack) =>
      stack.top match {
        case finish:FinishContext => acc.map { state =>
          if (state.message != finish.originalMessage) state
          else FinishMessageState(state.startContext, finish)
        }
        case fail:FailContext => acc.map { state =>
          if (state.message != fail.originalMessage) state
          else FailMessageState(state.startContext, fail)
        }
        case _ => acc.appendChild(stack.messages.reverse)
      }
    }
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

  lazy val startTime = messageState.message.time

  new RuntimeException().getStackTraceString

  lazy val allMessages: Seq[MessageState] = messageState :: children.flatMap(_.allMessages)

  def appendChild(newChild: Message): ReportTree = {
    ReportTree(messageState, children ::: List(ReportTree(MessageState(newChild))))
  }

  def appendChild(stack: List[Message]): ReportTree = {
    if (stack.size == 1) {
      appendChild(stack.head)
    } else {
      ReportTree(messageState, children map { child =>
        if (child.message != stack.head)
          child
        else
          child.appendChild(stack.tail)
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
    val messageRender = "%s:%s [%s]" format (position.reverse.mkString("."), message, cascadeState.toString)
    val childrenRender = children.zipWithIndex.flatMap{ case (tree: ReportTree, index: Int) => tree.render(index+1 :: position) }
    messageRender :: childrenRender
  }

  lazy val size: Int = allMessages.size
}