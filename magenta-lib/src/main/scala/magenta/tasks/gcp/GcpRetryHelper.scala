package magenta.tasks.gcp

import akka.actor.ActorSystem
import akka.pattern.after
import cats.data.NonEmptyList
import com.gu.management.Loggable

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * Borrowed from https://github.com/broadinstitute
  */
object GcpRetryHelper extends Loggable {
  type Predicate[A] = A => Boolean

  /**
    * A Future that has potentially been retried, with accumulated errors.
    * There are 3 cases:
    * 1. The future failed 1 or more times, and the final result is an error.
    *   - This is represented as {{{Left(NonEmptyList(errors))}}}
    * 2. The future failed 1 or more times, but eventually succeeded.
    *   - This is represented as {{{Right(List(errors), A)}}}
    * 3. The future succeeded the first time.
    *   - This is represented as {{{Right(List.empty, A)}}}
    */
  type RetryableFuture[A] = Future[Either[NonEmptyList[Throwable], (List[Throwable], A)]]

  def always[A]: Predicate[A] = _ => true

  def retryExponentially[T](pred: Predicate[Throwable] = always, failureLogMessage: String)(op: () => Future[T])(implicit actorSystem: ActorSystem): RetryableFuture[T] = {
    retryInternal(exponentialBackOffIntervals, pred, failureLogMessage)(op)
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
  def retryUntilSuccessOrTimeout[T](pred: Predicate[Throwable] = always, failureLogMessage: String)(interval: FiniteDuration, timeout: FiniteDuration)(op: () => Future[T])(implicit actorSystem: ActorSystem): RetryableFuture[T] = {
    val trialCount = Math.ceil(timeout / interval).toInt
    retryInternal(Seq.fill(trialCount)(interval), pred, failureLogMessage)(op)
  }

  private def retryInternal[T](backoffIntervals: Seq[FiniteDuration],
    pred: Predicate[Throwable],
    failureLogMessage: String)
    (op: () => Future[T])
    (implicit actorSystem: ActorSystem): RetryableFuture[T] = {
    implicit val ec: ExecutionContext = actorSystem.dispatcher

    def loop(remainingBackoffIntervals: Seq[FiniteDuration], errors: => List[Throwable]): RetryableFuture[T] = {
      op().map(x => Right((errors, x))).recoverWith {
        case t if pred(t) && remainingBackoffIntervals.nonEmpty =>
          logger.info(s"$failureLogMessage: ${remainingBackoffIntervals.size} retries remaining, retrying in ${remainingBackoffIntervals.head}", t)
          after(remainingBackoffIntervals.head, actorSystem.scheduler) {
            loop(remainingBackoffIntervals.tail, t :: errors)
          }

        case t =>
          if (remainingBackoffIntervals.isEmpty) {
            logger.info(s"$failureLogMessage: no retries remaining", t)
          } else {
            logger.info(s"$failureLogMessage: retries remain but predicate failed, not retrying", t)
          }

          Future.successful(Left(NonEmptyList(t, errors)))
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
  implicit def retryableFutureToFuture[A](af: RetryableFuture[A])(implicit executionContext: ExecutionContext): Future[A] = {
    af.flatMap {
      // take the head (most recent) error
      case Left(NonEmptyList(t, _)) => Future.failed(t)
      // return the successful result, throw out any errors
      case Right((_, a)) => Future.successful(a)
    }
  }

}
