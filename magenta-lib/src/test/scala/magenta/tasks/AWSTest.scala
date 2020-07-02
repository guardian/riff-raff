package magenta.tasks
import java.util.UUID

import magenta.tasks.CloudFormation.{TemplateBody, TemplateUrl}
import magenta.{ApiCredentials, KeyRing, StsDeploymentResources}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.core.util.DefaultSdkAutoConstructList
import software.amazon.awssdk.services.cloudformation.model.{ChangeSetType, CreateChangeSetRequest, Parameter}
import software.amazon.awssdk.services.sts.StsClient

import scala.collection.JavaConverters._

class AWSTest extends FlatSpec with Matchers with MockitoSugar {

  val stsClient = mock[StsClient]

  val deploymentResources = StsDeploymentResources(UUID.fromString("1-2-3-4-5"), stsClient)
  "AWS provider" should "use role provider if available" in {
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

  "CloudFormation.createChangeSetRequest" should "map all of the values into a request correctly" in {
    val param1 = Parameter.builder.parameterKey("param1").parameterValue("value1").build()
    val request: CreateChangeSetRequest = CloudFormation.createChangeSetRequest("test", ChangeSetType.CREATE, "testStack", Some(Map("tag1" -> "value1", "tag2" -> "value2")), TemplateBody("banana!"), List(param1))
    request.changeSetName shouldBe "test"
    request.stackName shouldBe "testStack"
    request.changeSetType shouldBe ChangeSetType.CREATE
    request.tags should have size 2
    request.tags.asScala.head.key shouldBe "tag1"
    request.tags.asScala.head.value shouldBe "value1"
    request.tags.asScala.last.key shouldBe "tag2"
    request.tags.asScala.last.value shouldBe "value2"
    request.templateBody shouldBe "banana!"
    request.parameters.asScala.head.parameterKey shouldBe "param1"
    request.parameters.asScala.head.parameterValue shouldBe "value1"
  }

  it should "use a templateUrl correctly" in {
    val request: CreateChangeSetRequest = CloudFormation.createChangeSetRequest("test", ChangeSetType.CREATE, "testStack", None, TemplateUrl("banana!"), Nil)
    request.templateBody shouldBe null
    request.templateURL shouldBe "banana!"
  }

  it should "set tags to the default instance instead of empty list" in {
    val request: CreateChangeSetRequest = CloudFormation.createChangeSetRequest("test", ChangeSetType.CREATE, "testStack", None, TemplateUrl("banana!"), Nil)
    request.tags shouldBe DefaultSdkAutoConstructList.getInstance
  }
}