package magenta

import java.io.IOException
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy
import software.amazon.awssdk.core.retry.conditions.RetryCondition

class PackageTest extends AnyFlatSpec with Matchers with MockitoSugar {
  val clientConfiguration: ClientOverrideConfiguration =
    ClientOverrideConfiguration
      .builder()
      .retryPolicy(
        RetryPolicy
          .builder()
          .retryCondition(RetryCondition.defaultRetryCondition())
          .backoffStrategy(BackoffStrategy.defaultStrategy())
          .numRetries(3)
          .throttlingBackoffStrategy(BackoffStrategy.none())
          .build()
      )
      .build()

  "retryOnException" should "work when no exception is thrown" in {
    var calls = 0
    def block: String = {
      calls += 1
      "banana"
    }

    val result = retryOnException(clientConfiguration)(block)
    result should be("banana")
    calls should be(1)
  }

  "retryOnException" should "work when an exception is thrown" in {
    var calls = 0
    def block: String = {
      calls += 1
      if (calls == 1)
        throw AwsServiceException
          .builder()
          .message("failure message")
          .cause(new IOException("IO cause"))
          .build()
      else
        "banana"
    }

    val result = retryOnException(clientConfiguration)(block)
    result should be("banana")
    calls should be(2)
  }

  "retryOnException" should "throw an exception if it fails consistently" in {
    var calls = 0
    def block: String = {
      calls += 1
      throw AwsServiceException
        .builder()
        .message("failure message")
        .cause(new IOException("IO cause"))
        .build()
    }

    an[AwsServiceException] should be thrownBy retryOnException(
      clientConfiguration
    )(block)

    /*
    numRetries is set to 3 in `clientConfiguration`
    1 initial attempt + 3 retries = 4

    Note, when using v2.5.14 of the AWS SDK this was 3.
    I couldn't work out why it differs in v2.17.15 and the above explanation is an attempt to rationalise it 😅.
     */
    calls should be(4)
  }

  "retryOnException" should "throw an exception immediately if not retryable" in {
    var calls = 0
    def block: String = {
      calls += 1
      throw AwsServiceException.builder().message("failure message").build()
    }

    an[AwsServiceException] should be thrownBy retryOnException(
      clientConfiguration
    )(block)

    calls should be(1)
  }
}
