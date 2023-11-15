package utils

import play.api.Logger

import scala.util.{Failure, Success, Try}
import scala.annotation.{nowarn, tailrec}

trait Retriable {
  def log: Logger

  def retryUpTo[T](maxAttempts: Int, message: Option[String] = None)(
      thunk: => T
  ): Try[T] = {
    @nowarn // It would fail on the following inputs: Cons(), Empty
    @tailrec def go(s: Stream[Try[T]], n: Int): Try[T] = s match {
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
    }

    val thunkStream = Stream.continually(Try(thunk))
    go(thunkStream, 1)
  }
}
