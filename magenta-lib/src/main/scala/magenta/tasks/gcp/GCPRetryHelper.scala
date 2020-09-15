package magenta.tasks.gcp

import cats.data.NonEmptyList
import com.gu.management.Loggable
import magenta.DeployReporter

import scala.concurrent.duration._

/**
  * Borrowed from https://github.com/broadinstitute
  */
object GCPRetryHelper extends Loggable {

  def log(reporter: DeployReporter, message: String, t: Throwable): Unit = {
    logger.info(message, t)
    reporter.verbose(message)
  }

  type Predicate[A] = A => Boolean

  /**
    * A task that has potentially been retried, with accumulated errors.
    * There are 3 cases:
    * 1. The task failed 1 or more times, and the final result is an error.
    *   - This is represented as {{{Left(NonEmptyList(errors))}}}
    * 2. The task failed 1 or more times, but eventually succeeded.
    *   - This is represented as {{{Right(List(errors), A)}}}
    * 3. The task succeeded the first time.
    *   - This is represented as {{{Right(List.empty, A)}}}
    */
  type Result[A] = Either[Throwable, A]
  type Retryable[A] = Either[NonEmptyList[Throwable], (List[Throwable], A)]

  def always[A]: Predicate[A] = _ => true

  def after[T](duration: FiniteDuration)(task: => Retryable[T]): Retryable[T] = {
    Thread.sleep(duration.toMillis)
    task
  }

  def retryExponentially[T](
    reporter: DeployReporter,
    pred: Predicate[Throwable] = always,
    failureLogMessage: String
  )(op: => Result[T]): Retryable[T] = {
    retryInternal(reporter, exponentialBackOffIntervals, pred, failureLogMessage)(op)
  }

  /**
    * will retry at the given interval until success or the overall timeout has passed
    * @param pred which failures to retry
    * @param interval how often to retry
    * @param timeout how long from now to give up
    * @param op what to try
    * @tparam T
    * @return
    */
  def retryUntilSuccessOrTimeout[T](
    reporter: DeployReporter,
    pred: Predicate[Throwable] = always,
    failureLogMessage: String
  )(interval: FiniteDuration,
    timeout: FiniteDuration
  )(op: => Result[T]): Retryable[T] = {

    val trialCount = Math.ceil(timeout / interval).toInt
    retryInternal(reporter, Seq.fill(trialCount)(interval), pred, failureLogMessage)(op)
  }

  private def retryInternal[T](
    reporter: DeployReporter,
    backoffIntervals: Seq[FiniteDuration],
    pred: Predicate[Throwable],
    failureLogMessage: String)
    (op: => Result[T]): Retryable[T] = {

    def loop(remainingBackoffIntervals: Seq[FiniteDuration], errors: => List[Throwable]): Retryable[T] = {
      op match {
        case Right(x) => Right((errors, x))

        case Left(t) if pred(t) && remainingBackoffIntervals.nonEmpty =>
          log(reporter, s"$failureLogMessage: ${remainingBackoffIntervals.size} retries remaining, retrying in ${remainingBackoffIntervals.head}", t)
          after(remainingBackoffIntervals.head) {
            loop(remainingBackoffIntervals.tail, t :: errors)
          }

        case Left(t) =>
          if (remainingBackoffIntervals.isEmpty) {
            log(reporter, s"$failureLogMessage: no retries remaining", t)
          } else {
            log(reporter, s"$failureLogMessage: retries remain but predicate failed, not retrying", t)
          }

          Left(NonEmptyList(t, errors))
      }
    }

    loop(backoffIntervals, List.empty)
  }

  protected def exponentialBackOffIntervals: Seq[FiniteDuration] = {
    val plainIntervals = Seq(1000 milliseconds, 2000 milliseconds, 4000 milliseconds, 8000 milliseconds, 16000 milliseconds, 32000 milliseconds)
    plainIntervals.map(i => addJitter(i, 1000 milliseconds))
  }

  def addJitter(baseTime: FiniteDuration, maxJitterToAdd: Duration): FiniteDuration = {
    baseTime + ((scala.util.Random.nextFloat * maxJitterToAdd.toNanos) nanoseconds)
  }

  /**
    * Converts an RetryableFuture[A] to a Future[A].
    */
  implicit def retryableToResult[A](af: Retryable[A]): Result[A] = {
    af match {
      // take the head (most recent) error
      case Left(NonEmptyList(t, _)) => Left(t)
      // return the successful result, throw out any errors
      case Right((_, a)) => Right(a)
    }
  }

}
