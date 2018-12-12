package migration
package data

import org.mongodb.scala._

sealed abstract class ColType { self =>
  override def toString = self match {
    case ColString(len, true)  => s"varchar($len)"
    case ColString(len, false) => s"char($len)"
    case ColUUID               => "uuid"
  }
}

final case class ColString(len: Int, varlen: Boolean) extends ColType
case object ColUUID extends ColType
