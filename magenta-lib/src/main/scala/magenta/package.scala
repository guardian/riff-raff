package magenta

import java.io.Closeable

import com.amazonaws.{AmazonClientException, ClientConfiguration}
import com.gu.management.Loggable

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

object `package` extends Loggable {
  def transpose[A](xs: Seq[Seq[A]]): Seq[Seq[A]] = xs.filter(_.nonEmpty) match {
    case Nil => Nil
    case ys: Seq[Seq[A]] => ys.map{ _.head } +: transpose(ys.map{ _.tail })
  }

  implicit class Seq2TransposeBy[A](seq: Seq[A]) {
    def transposeBy[K](f: A => K)(implicit ord:Ordering[K]): Seq[A] = {
      val listOfGroups = seq.groupBy(f).toList.sortBy(_._1).map(_._2)
      transpose(listOfGroups).fold(Nil)(_ ++ _)
    }
  }

  def withResource[C <: Closeable, T](resource: C)(f: C => T): T = {
    try {
      f(resource)
    } finally {
      resource.close()
    }
  }

  @tailrec
  def retryOnException[T](config: ClientConfiguration, currentAttempt: Int = 1)(f: => T): T = {
    val policy = config.getRetryPolicy
    val retries = policy.getMaxErrorRetry

    // use the client config retry logic - we pass in null for the request as no SDK implementations actually use it
    def shouldRetryFor(ace: AmazonClientException) = policy.getRetryCondition.shouldRetry(null, ace, currentAttempt)

    Try(f) match {
      case Success(result) => result
      case Failure(exception:AmazonClientException) if currentAttempt <= retries && shouldRetryFor(exception) =>
        // use client config to calculate delay - pass in null for request as no SDK implementations actually use it
        val delay = policy.getBackoffStrategy.delayBeforeNextRetry(null, exception, currentAttempt)
        logger.warn(s"Client exception encountered, retrying in ${delay}ms")
        Thread.sleep(delay)
        retryOnException(config, currentAttempt + 1)(f)
      case Failure(t) => throw t
    }
  }

  /** This can be used when you have high confidence it will never be reached. An example might be exhaustive cases in
    * a match statement. */
  def `wtf?` : Nothing = throw new IllegalStateException("WTF?")
}