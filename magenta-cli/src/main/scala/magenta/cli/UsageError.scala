package magenta
package cli


class UsageError(message: String) extends Exception(message)

object UsageError {
  def apply(msg: String) = throw new UsageError(msg)
}