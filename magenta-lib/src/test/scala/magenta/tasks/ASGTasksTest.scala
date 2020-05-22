package magenta.tasks

import java.util.UUID

import magenta.artifact.S3Path
import magenta.fixtures._
import magenta.{KeyRing, Stage, _}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import software.amazon.awssdk.services.autoscaling.AutoScalingClient
import software.amazon.awssdk.services.autoscaling.model.{AutoScalingGroup, SetDesiredCapacityRequest}

class ASGTasksTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing: KeyRing = KeyRing()
  val reporter: DeployReporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  val resources = mock[DeploymentResources].copy(reporter = reporter)
  val deploymentTypes: Seq[StubDeploymentType] = Seq(stubDeploymentType(name = "testDeploymentType", actionNames = Seq("testAction")))

  it should "double the size of the autoscaling group" in {
    val asg = AutoScalingGroup.builder()
      .desiredCapacity(3)
      .autoScalingGroupName("test")
      .maxSize(10)
      .build()
    val asgClientMock = mock[AutoScalingClient]

    val p = DeploymentPackage("test", App("app"), Map.empty, "testDeploymentType", S3Path("artifact-bucket", "project/123/test"),
      deploymentTypes)

    val task = DoubleSize(p, Stage("PROD"), stack, Region("eu-west-1"))

    task.execute(asg, resources, stopFlag = false, asgClientMock)

    verify(asgClientMock).setDesiredCapacity(
      SetDesiredCapacityRequest.builder().autoScalingGroupName("test").desiredCapacity(6).build()
    )
  }

  it should "fail if you do not have the capacity to deploy" in {
    val asg = AutoScalingGroup.builder()
      .autoScalingGroupName("test")
      .minSize(1)
      .desiredCapacity(1)
      .maxSize(1)
      .build()

    val asgClientMock = mock[AutoScalingClient]

    val p = DeploymentPackage("test", App("app"), Map.empty, "testDeploymentType", S3Path("artifact-bucket", "project/123/test"),
      deploymentTypes)

    val task = CheckGroupSize(p, Stage("PROD"), stack, Region("eu-west-1"))

    val thrown = intercept[FailException](task.execute(asg, resources, stopFlag = false, asgClientMock))

    thrown.getMessage should startWith ("Autoscaling group does not have the capacity")
  }
}
