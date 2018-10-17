package utils

import play.api.Logger

trait LogAndSquashBehaviour {
  val log: Logger

  implicit class RichEitherThrowable[T](either: Either[Throwable, T]) {
    def logAndSquashException(default: T, message: Option[String] = None): T = {
      either match {
        case Right(t) => t
        case Left(throwable) =>
          val errorMessage = "Squashing uncaught exception%s" format message.map("whilst %s" format _).getOrElse("")
          log.error(errorMessage, throwable)
          default
      }
    }

    def retry(max: Int)(f: Either[Throwable, T] => Either[Throwable, T]): Either[Throwable, T] =
      either match {
        case Left(_) if max > 0 => f(either).retry(max - 1)(f)
        case _ => either
      }
  }
}
