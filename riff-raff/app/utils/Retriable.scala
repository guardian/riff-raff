package utils

import play.api.Logger
import scala.util.{ Try, Success, Failure }

trait Retriable {
  def log: Logger

  def retryUpTo[T](maxAttempts: Int, message: Option[String] = None)(thunk: => T): Try[T] = {
    val thunkStream = Stream.continually(Try(thunk)).take(maxAttempts)
    thunkStream.zipWithIndex.foldRight[Try[T]](Failure(new RuntimeException("Giving up after %d attempts" format maxAttempts))) { 
      case ((a: Success[T], i), _) =>
        message.foreach(m => log.debug("Completed after %d attempts: %s".format(i + 1, m)))
        a
      case ((Failure(t), i), b) =>
        val errorMessage = "Caught exception %s (attempt #%d)".format(message.map("whilst %s" format _).getOrElse(""), i + 1)
        log.error(errorMessage, t)
        b
    }
    thunkStream.find(_.isSuccess).getOrElse(thunkStream.head)
  }
}