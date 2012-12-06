package magenta.tasks

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import magenta.{SystemUser, Stage, KeyRing}
import org.scalatest.mock.MockitoSugar
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.{Instance => ASGInstance, LifecycleState, AutoScalingGroup, DescribeAutoScalingGroupsResult, TagDescription}

import org.mockito.Mockito._
import collection.JavaConversions._
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.{Instance => ELBInstance, InstanceState, DescribeInstanceHealthResult, DescribeInstanceHealthRequest}

class ASGTest extends FlatSpec with ShouldMatchers with MockitoSugar {
  it should "find the matching auto-scaling group with App tagging" in {
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

  it should "find the matching auto-scaling group with Role tagging" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val asg = new ASG {
      override def client(implicit keyRing: KeyRing) = asgClientMock
    }

    val desiredGroup = AutoScalingGroup(("Role" -> "example"), ("Stage" -> "PROD"))

    when (asgClientMock.describeAutoScalingGroups) thenReturn (
      new DescribeAutoScalingGroupsResult().withAutoScalingGroups(List(
        desiredGroup,
        AutoScalingGroup(("Role" -> "other"), ("Stage" -> "PROD")),
        AutoScalingGroup(("Role" -> "example"), ("Stage" -> "TEST"))
      ))
    )

    asg.withPackageAndStage("example", Stage("PROD")) should be (Some(desiredGroup))
  }

  it should "wait for instances in ELB to stabilise if there is one" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val elbClientMock = mock[AmazonElasticLoadBalancingClient]

    val asg = new ASG {
      override def client(implicit keyRing: KeyRing) = asgClientMock
      override def elb = new ELB {
        override def client(implicit keyRing: KeyRing) = elbClientMock
      }
    }

    val group = AutoScalingGroup("elb", ("Role" -> "example"), ("Stage" -> "PROD")).withDesiredCapacity(1)

    when (elbClientMock.describeInstanceHealth(
      new DescribeInstanceHealthRequest().withLoadBalancerName("elb")
    )).thenReturn(new DescribeInstanceHealthResult().withInstanceStates(new InstanceState().withState("")))

    asg.isStabilized(group) should be (false)

    when (elbClientMock.describeInstanceHealth(
      new DescribeInstanceHealthRequest().withLoadBalancerName("elb")
    )).thenReturn(new DescribeInstanceHealthResult().withInstanceStates(new InstanceState().withState("InService")))

    asg.isStabilized(group) should be (true)
  }

  it should "just check ASG health for stability if there is no ELB" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val elbClientMock = mock[AmazonElasticLoadBalancingClient]

    val asg = new ASG {
      override def client(implicit keyRing: KeyRing) = asgClientMock
      override def elb = new ELB {
        override def client(implicit keyRing: KeyRing) = elbClientMock
      }
    }

    val group = AutoScalingGroup(("Role" -> "example"), ("Stage" -> "PROD"))
      .withDesiredCapacity(1).withInstances(new ASGInstance().withHealthStatus("Foobar"))

    when (elbClientMock.describeInstanceHealth(
      new DescribeInstanceHealthRequest().withLoadBalancerName("elb")
    )).thenReturn(new DescribeInstanceHealthResult().withInstanceStates(new InstanceState().withState("")))

    asg.isStabilized(group) should be (false)

    val updatedGroup = AutoScalingGroup(("Role" -> "example"), ("Stage" -> "PROD"))
      .withDesiredCapacity(1).withInstances(new ASGInstance().withLifecycleState(LifecycleState.InService))

    asg.isStabilized(updatedGroup) should be (true)
  }

  object AutoScalingGroup {
    def apply(tags: (String, String)*) = new AutoScalingGroup().withTags(tags map {
      case (key, value) => new TagDescription().withKey(key).withValue(value)
    })
    def apply(elbName: String, tags: (String, String)*) = new AutoScalingGroup().withTags(tags map {
      case (key, value) => new TagDescription().withKey(key).withValue(value)
    }).withLoadBalancerNames(elbName)
  }

  implicit val fakeKeyRing = KeyRing(SystemUser(None))
}
