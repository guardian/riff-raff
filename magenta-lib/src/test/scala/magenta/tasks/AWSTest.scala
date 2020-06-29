package magenta.tasks
import java.util.UUID

import magenta.{ApiCredentials, KeyRing, StsDeploymentResources}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.services.sts.StsClient

class AWSTest extends FlatSpec with Matchers with MockitoSugar {

  val stsClient = mock[StsClient]

  val deploymentResources = StsDeploymentResources(UUID.fromString("1-2-3-4-5"), stsClient)
  it should "use role provider if available" in {
    val keyring = KeyRing(apiCredentials = Map(
      "aws"-> ApiCredentials("aws-role", "role", "secret", Some("comment")),
      "aws-role"-> ApiCredentials("aws-role", "role", "no secret", Some("comment"))))
    val provider = AWS.provider(keyring, deploymentResources)
    provider.toString.contains("StsAssumeRoleCredentialsProvider") shouldBe true

  }
  it should "use static credentials provider if available otherwise" in {
    val keyring = KeyRing(apiCredentials = Map(
      "aws"-> ApiCredentials("aws-role", "role", "secret", Some("comment"))))
    val provider = AWS.provider(keyring, deploymentResources)
    provider.toString.contains("StaticCredentialsProvider") shouldBe true
  }

  it should "throw an exception otherwise" in {
    val keyring = KeyRing(apiCredentials = Map())
    assertThrows[IllegalArgumentException](AWS.provider(keyring, deploymentResources))

  }
}