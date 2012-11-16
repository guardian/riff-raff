package persistence

import magenta._

object `package` {
  implicit def messageStack2id(stack: MessageStack) = new {
    def id: String = "%08x" format stack.hashCode
  }

  implicit def message2contextInfo(message: Message) = new {
    def isContext: Boolean = isStartContext || isEndContext
    def isStartContext: Boolean = message.getClass == classOf[StartContext]
    def isEndContext: Boolean = isFinishContext || isFailContext
    def isFinishContext: Boolean = message.getClass == classOf[FinishContext]
    def isFailContext: Boolean = message.getClass == classOf[FailContext]
  }

  implicit def messageStack2parentMessage(stack: MessageStack) = new {
    def parentMessage: Option[Message] = stack.messages.tail.headOption
  }

  implicit def message2MessageDocument(message: Message) = new {
    def asMessageDocument: MessageDocument = MessageDocument(message)
  }
}