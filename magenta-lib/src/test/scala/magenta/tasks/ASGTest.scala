package magenta.tasks

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import magenta.{SystemUser, Stage, KeyRing}
import org.scalatest.mock.MockitoSugar
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.{AutoScalingGroup, DescribeAutoScalingGroupsResult}

import org.mockito.Mockito._
import collection.JavaConversions._
import com.amazonaws.services.autoscaling.model.TagDescription

class ASGTest extends FlatSpec with ShouldMatchers with MockitoSugar {
  it should "find the matching auto-scaling group" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val asg = new ASG {
      override def client(implicit keyRing: KeyRing) = asgClientMock
    }

    val desiredGroup = AutoScalingGroup(("App" -> "example"), ("Stage" -> "PROD"))

    when (asgClientMock.describeAutoScalingGroups) thenReturn (
      new DescribeAutoScalingGroupsResult().withAutoScalingGroups(List(
        desiredGroup,
        AutoScalingGroup(("App" -> "other"), ("Stage" -> "PROD")),
        AutoScalingGroup(("App" -> "example"), ("Stage" -> "TEST"))
      ))
    )

    asg.withPackageAndStage("example", Stage("PROD")) should be (Some(desiredGroup))
  }

  object AutoScalingGroup {
    def apply(tags: (String, String)*) = new AutoScalingGroup().withTags(tags map {
      case (key, value) => new TagDescription().withKey(key).withValue(value)
    })
  }

  implicit val fakeKeyRing = KeyRing(SystemUser(None))
}
