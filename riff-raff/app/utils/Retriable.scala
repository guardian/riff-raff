package utils

import play.api.Logger

import scala.util.{Failure, Success, Try}
import scala.annotation.tailrec

trait Retriable {
  def log: Logger

  def retryUpTo[T](maxAttempts: Int, message: Option[String] = None)(
      thunk: => T
  ): Try[T] = {
    @tailrec def go(s: LazyList[Try[T]], n: Int): Try[T] = s match {
      case (f @ Failure(t)) #:: tail =>
        val errorMessage = "Caught exception %s (attempt #%d)".format(
          message.map("whilst %s" format _).getOrElse(""),
          n
        )
        log.error(errorMessage, t)
        if (n == maxAttempts)
          f
        else
          go(tail, n + 1)
      case (s: Success[_]) #:: _ =>
        message.foreach(m =>
          log.debug("Completed after %d attempts: %s".format(n, m))
        )
        s
      case _ =>
        Failure(new IllegalStateException("Unexpected empty LazyList"))
    }

    val thunkStream = LazyList.continually(Try(thunk))
    go(thunkStream, 1)
  }
}
