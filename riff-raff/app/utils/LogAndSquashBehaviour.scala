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

    def retry[E, A](max: Int)(f: A => Either[Throwable, A])(z: A, e: Throwable): Either[Throwable, A] =
      if (max == 0)
        Left(e)
      else
        f(z).fold(retry(max - 1)(f)(z, _), Right(_))
  }
}
