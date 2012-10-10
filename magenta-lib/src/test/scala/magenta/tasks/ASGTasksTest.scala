package magenta.tasks

import com.amazonaws.services.autoscaling.model.{UpdateAutoScalingGroupRequest, SetDesiredCapacityRequest, AutoScalingGroup}
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import magenta._
import org.mockito.Mockito._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import magenta.KeyRing
import magenta.Stage
import org.scalatest.mock.MockitoSugar

class ASGTasksTest extends FlatSpec with ShouldMatchers with MockitoSugar {
  it should "double the size of the autoscaling group" in {
    val asg = new AutoScalingGroup().withDesiredCapacity(3).withAutoScalingGroupName("test").withMaxSize(10)
    val asgClientMock = mock[AmazonAutoScalingClient]

    val task = new DoubleSize("app", Stage("PROD")) {
      override def client(implicit keyRing: KeyRing) = asgClientMock
      override def withPackageAndStage(packageName: String, stage: Stage)(implicit keyRing: KeyRing) = Some(asg)
    }

    task.execute(fakeKeyRing)

    verify(asgClientMock).setDesiredCapacity(
      new SetDesiredCapacityRequest().withAutoScalingGroupName("test").withDesiredCapacity(6)
    )
  }

  it should "increase the max size of the autoscaling group if less than double desired capacity" in {
    val asg = new AutoScalingGroup().withDesiredCapacity(3).withAutoScalingGroupName("test").withMaxSize(5)
    val asgClientMock = mock[AmazonAutoScalingClient]

    val task = new DoubleSize("app", Stage("PROD")) {
      override def client(implicit keyRing: KeyRing) = asgClientMock
      override def withPackageAndStage(packageName: String, stage: Stage)(implicit keyRing: KeyRing) = Some(asg)
    }

    task.execute(fakeKeyRing)

    verify(asgClientMock).updateAutoScalingGroup(
      new UpdateAutoScalingGroupRequest().withAutoScalingGroupName("test").withMaxSize(6)
    )
  }

  val fakeKeyRing = KeyRing(SystemUser(None))
}
