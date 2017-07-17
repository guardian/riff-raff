package magenta

import java.io.IOException

import com.amazonaws.{AmazonClientException, ClientConfiguration}
import com.amazonaws.retry.{PredefinedRetryPolicies, RetryPolicy}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

class PackageTest extends FlatSpec with Matchers with MockitoSugar {
  val clientConfiguration = new ClientConfiguration().
    withRetryPolicy(new RetryPolicy(
      PredefinedRetryPolicies.DEFAULT_RETRY_CONDITION,
      PredefinedRetryPolicies.DEFAULT_BACKOFF_STRATEGY,
      3,
      false
    ))

  "retryOnException" should "work when no exception is thrown" in {
    var calls = 0
    def block:String = {
      calls += 1
      "banana"
    }

    val result = retryOnException(clientConfiguration)(block)
    result should be("banana")
    calls should be(1)
  }

  "retryOnException" should "work when an exception is thrown" in {
    var calls = 0
    def block:String = {
      calls += 1
      if (calls == 1)
        throw new AmazonClientException("failure message", new IOException("IO cause"))
      else
        "banana"
    }

    val result = retryOnException(clientConfiguration)(block)
    result should be("banana")
    calls should be(2)
  }

  "retryOnException" should "throw an exception if it fails consistently" in {
    var calls = 0
    def block:String = {
      calls += 1
      throw new AmazonClientException("failure message", new IOException("IO cause"))
    }

    an [AmazonClientException] should be thrownBy retryOnException(clientConfiguration)(block)

    calls should be(4)
  }

  "retryOnException" should "throw an exception immediately if not retryable" in {
    var calls = 0
    def block:String = {
      calls += 1
      throw new AmazonClientException("failure message")
    }

    an [AmazonClientException] should be thrownBy retryOnException(clientConfiguration)(block)

    calls should be(1)
  }
}
