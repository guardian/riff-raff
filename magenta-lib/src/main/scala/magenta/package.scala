package magenta

import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.{RetryPolicy, RetryPolicyContext}

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

object `package` extends Loggable {
  def transpose[A](xs: Seq[Seq[A]]): Seq[Seq[A]] = xs.filter(_.nonEmpty) match {
    case Nil             => Nil
    case ys: Seq[Seq[A]] => ys.map { _.head } +: transpose(ys.map { _.tail })
  }

  implicit class Seq2TransposeBy[A](seq: Seq[A]) {
    def transposeBy[K](f: A => K)(implicit ord: Ordering[K]): Seq[A] = {
      val listOfGroups = seq.groupBy(f).toList.sortBy(_._1).map(_._2)
      transpose(listOfGroups).fold(Nil)(_ ++ _)
    }
  }

  def withResource[C <: AutoCloseable, T](resource: C)(f: C => T): T = {
    try {
      f(resource)
    } finally {
      resource.close()
    }
  }

  @tailrec
  def retryOnException[T](
      config: ClientOverrideConfiguration,
      currentAttempt: Int = 1
  )(f: => T): T = {
    val policy: RetryPolicy = config.retryPolicy.get
    val retries = policy.numRetries

    // use the client config retry logic
    def shouldRetryFor(ase: AwsServiceException): Boolean = {
      val retryContext = RetryPolicyContext
        .builder()
        .exception(ase)
        .retriesAttempted(currentAttempt)
        .build()
      policy.retryCondition.shouldRetry(retryContext)
    }

    Try(f) match {
      case Success(result) => result
      case Failure(exception: AwsServiceException)
          if currentAttempt <= retries && shouldRetryFor(exception) =>
        // use client config to calculate delay - pass in null for request as no SDK implementations actually use it
        val retryContext = RetryPolicyContext
          .builder()
          .exception(exception)
          .retriesAttempted(currentAttempt)
          .build()
        val delay = policy.backoffStrategy
          .computeDelayBeforeNextRetry(retryContext)
          .getSeconds
        logger.warn(s"Client exception encountered, retrying in ${delay}ms")
        Thread.sleep(delay)
        retryOnException(config, currentAttempt + 1)(f)
      case Failure(t) => throw t
    }
  }

  /** This can be used when you have high confidence it will never be reached.
    * An example might be exhaustive cases in a match statement.
    */
  def `wtf?`: Nothing = throw new IllegalStateException("WTF?")
}
