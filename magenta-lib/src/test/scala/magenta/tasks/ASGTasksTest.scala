package magenta.tasks

import java.util.UUID

import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.{AutoScalingGroup, SetDesiredCapacityRequest}
import magenta.artifact.S3Path
import magenta.deployment_type.DeploymentType
import magenta.fixtures._
import magenta.{KeyRing, Stage, _}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

class ASGTasksTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  val deploymentTypes = Seq(stubDeploymentType(name="testDeploymentType", actionNames = Seq("testAction")))

  it should "double the size of the autoscaling group" in {
    val asg = new AutoScalingGroup().withDesiredCapacity(3).withAutoScalingGroupName("test").withMaxSize(10)
    val asgClientMock = mock[AmazonAutoScalingClient]

    val p = DeploymentPackage("test", Seq(App("app")), Map.empty, "testDeploymentType", S3Path("artifact-bucket", "project/123/test"),
      true, deploymentTypes)

    val task = new DoubleSize(p, Stage("PROD"), UnnamedStack, Region("eu-west-1"))

    task.execute(asg, reporter, false, asgClientMock)

    verify(asgClientMock).setDesiredCapacity(
      new SetDesiredCapacityRequest().withAutoScalingGroupName("test").withDesiredCapacity(6)
    )
  }

  it should "fail if you do not have the capacity to deploy" in {
    val asg = new AutoScalingGroup().withAutoScalingGroupName("test")
      .withMinSize(1).withDesiredCapacity(1).withMaxSize(1)

    val asgClientMock = mock[AmazonAutoScalingClient]

    val p = DeploymentPackage("test", Seq(App("app")), Map.empty, "testDeploymentType", S3Path("artifact-bucket", "project/123/test"),
      true, deploymentTypes)

    val task = new CheckGroupSize(p, Stage("PROD"), UnnamedStack, Region("eu-west-1"))

    val thrown = intercept[FailException](task.execute(asg, reporter, false, asgClientMock))

    thrown.getMessage should startWith ("Autoscaling group does not have the capacity")
  }
}
