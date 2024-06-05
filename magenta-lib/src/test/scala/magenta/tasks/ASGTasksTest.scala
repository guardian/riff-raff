package magenta.tasks

import java.util.UUID
import magenta.artifact.S3Path
import magenta.deployment_type.AutoScalingGroupInfo
import magenta.fixtures._
import magenta.{KeyRing, Stage, _}
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.services.autoscaling.AutoScalingClient
import software.amazon.awssdk.services.autoscaling.model.{
  AutoScalingGroup,
  SetDesiredCapacityRequest
}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient

import scala.concurrent.ExecutionContext.global

class ASGTasksTest extends AnyFlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  val reporter: DeployReporter =
    DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  val deploymentTypes: Seq[StubDeploymentType] = Seq(
    stubDeploymentType(
      name = "testDeploymentType",
      actionNames = Seq("testAction")
    )
  )

  // TODO: these tests regularly fail with `An illegal reflective access
  // operation has occurred`. This appears to be caused by Mockito when mocking
  // some of the Amazon types. We should investigate this further and re-enable
  // these tests once fixed.
  it should "double the size of the autoscaling group" ignore {
    val asg = AutoScalingGroup
      .builder()
      .desiredCapacity(3)
      .autoScalingGroupName("test")
      .maxSize(10)
      .build()
    val info = AutoScalingGroupInfo(asg, Nil)
    implicit val asgClientMock = mock[AutoScalingClient]

    val p = DeploymentPackage(
      "test",
      App("app"),
      Map.empty,
      "testDeploymentType",
      S3Path("artifact-bucket", "project/123/test"),
      deploymentTypes
    )

    val task = DoubleSize(info, Region("eu-west-1"))
    val resources = DeploymentResources(
      reporter,
      null,
      mock[S3Client],
      mock[StsClient],
      global
    )
    task.execute(asg, resources, stopFlag = false)

    verify(asgClientMock).setDesiredCapacity(
      SetDesiredCapacityRequest
        .builder()
        .autoScalingGroupName("test")
        .desiredCapacity(6)
        .build()
    )
  }

  it should "fail if you do not have the capacity to deploy" in {
    val asg = AutoScalingGroup
      .builder()
      .autoScalingGroupName("test")
      .minSize(1)
      .desiredCapacity(1)
      .maxSize(1)
      .build()
    val info = AutoScalingGroupInfo(asg, Nil)

    implicit val asgClientMock = mock[AutoScalingClient]

    val task = CheckGroupSize(info, Region("eu-west-1"))

    val resources = DeploymentResources(
      reporter,
      null,
      mock[S3Client],
      mock[StsClient],
      global
    )

    val thrown = intercept[FailException](
      task.execute(asg, resources, stopFlag = false)
    )

    thrown.getMessage should startWith(
      "Autoscaling group does not have the capacity"
    )
  }
}
