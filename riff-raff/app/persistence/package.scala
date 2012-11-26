package persistence

import magenta._
import scala.util.MurmurHash

object `package` {
  implicit def messageStack2id(stack: MessageStack) = new {
    val messages = stack.messages.flatMap{ message =>
      if (message.isInstanceOf[ContextMessage])
        Seq(message, message.asInstanceOf[ContextMessage].originalMessage)
      else
        Seq(message)
    }
    val messagesAndNames = messages.flatMap(message => Seq(message.getClass.getName, message))
    var hash = MurmurHash.arrayHash(messagesAndNames.toArray)
    def id: String = {
      "%08x" format hash
    }
  }

  implicit def int2asHex(int: Int) = new {
    def asHex: String = "%08x" format int
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