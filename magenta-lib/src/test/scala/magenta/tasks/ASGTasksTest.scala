package magenta.tasks

import java.io.File
import java.util.UUID

import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.{AutoScalingGroup, SetDesiredCapacityRequest}
import magenta.artifact.S3Package
import magenta.{KeyRing, Stage, _}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

class ASGTasksTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  implicit val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())

  it should "double the size of the autoscaling group" in {
    val asg = new AutoScalingGroup().withDesiredCapacity(3).withAutoScalingGroupName("test").withMaxSize(10)
    val asgClientMock = mock[AmazonAutoScalingClient]

    val p = DeploymentPackage("test", Seq(App("app")), Map.empty, "test", S3Package("artifact-bucket", "project/123/test"))

    val task = new DoubleSize(p, Stage("PROD"), UnnamedStack) {
      override def client(implicit keyRing: KeyRing) = asgClientMock
      override def groupForAppAndStage(pkg: DeploymentPackage,  stage: Stage, stack: Stack)
                                      (implicit keyRing: KeyRing, reporter: DeployReporter) = asg
    }

    task.execute(reporter)

    verify(asgClientMock).setDesiredCapacity(
      new SetDesiredCapacityRequest().withAutoScalingGroupName("test").withDesiredCapacity(6)
    )
  }

  it should "fail if you do not have the capacity to deploy" in {
    val asg = new AutoScalingGroup().withAutoScalingGroupName("test")
      .withMinSize(1).withDesiredCapacity(1).withMaxSize(1)

    val asgClientMock = mock[AmazonAutoScalingClient]

    val p = DeploymentPackage("test", Seq(App("app")), Map.empty, "test", S3Package("artifact-bucket", "project/123/test"))

    val task = new CheckGroupSize(p, Stage("PROD"), UnnamedStack) {
      override def client(implicit keyRing: KeyRing) = asgClientMock
      override def groupForAppAndStage(pkg: DeploymentPackage, stage: Stage, stack: Stack)
                                      (implicit keyRing: KeyRing, reporter: DeployReporter) = asg
    }

    val thrown = intercept[FailException](task.execute(reporter))

    thrown.getMessage should startWith ("Autoscaling group does not have the capacity")

  }
}
