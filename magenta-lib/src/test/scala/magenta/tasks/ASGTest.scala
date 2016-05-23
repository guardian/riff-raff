package magenta.tasks

import org.scalatest.{FlatSpec, Matchers}
import magenta._
import org.scalatest.mock.MockitoSugar
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.{Instance => ASGInstance, _}
import org.mockito.Mockito._
import org.mockito.Matchers._

import collection.JavaConversions._
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.{DescribeInstanceHealthRequest, DescribeInstanceHealthResult, InstanceState, Instance => ELBInstance}
import magenta.{App, KeyRing, Stage, SystemUser}
import java.io.File
import java.util.UUID

class ASGTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing = KeyRing(SystemUser(None))
  implicit val logger = DeployLogger.rootLoggerFor(UUID.randomUUID(), fixtures.parameters())

  it should "find the matching auto-scaling group with App tagging" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val asg = new ASG {
      override def client(implicit keyRing: KeyRing) = asgClientMock
    }

    val desiredGroup = AutoScalingGroup("App" -> "example", "Stage" -> "PROD")

    when (asgClientMock.describeAutoScalingGroups(any[DescribeAutoScalingGroupsRequest])) thenReturn
      new DescribeAutoScalingGroupsResult().withAutoScalingGroups(List(
        desiredGroup,
        AutoScalingGroup("App" -> "other", "Stage" -> "PROD"),
        AutoScalingGroup("App" -> "example", "Stage" -> "TEST")
      ))

    val p = DeploymentPackage("example", Seq(App("app")), Map.empty, "nowt much", new File("/tmp/packages/webapp"))
    asg.groupForAppAndStage(p, Stage("PROD"), UnnamedStack) should be (desiredGroup)
  }

  it should "find the matching auto-scaling group with Role tagging" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val asg = new ASG {
      override def client(implicit keyRing: KeyRing) = asgClientMock
    }

    val desiredGroup = AutoScalingGroup("Role" -> "example", "Stage" -> "PROD")

    when (asgClientMock.describeAutoScalingGroups(any[DescribeAutoScalingGroupsRequest])) thenReturn
      new DescribeAutoScalingGroupsResult().withAutoScalingGroups(List(
        desiredGroup,
        AutoScalingGroup(("Role" -> "other"), ("Stage" -> "PROD")),
        AutoScalingGroup(("Role" -> "example"), ("Stage" -> "TEST"))
      ))

    val p = DeploymentPackage("example", Seq(App("app")), Map.empty, "nowt much", new File("/tmp/packages/webapp"))
    asg.groupForAppAndStage(p, Stage("PROD"), UnnamedStack) should be (desiredGroup)
  }

  it should "find the matching auto-scaling group with Stack and App tags" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val asg = new ASG {
      override def client(implicit keyRing: KeyRing) = asgClientMock
    }

    val desiredGroup = AutoScalingGroup("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "PROD")

    when (asgClientMock.describeAutoScalingGroups(any[DescribeAutoScalingGroupsRequest])) thenReturn
      new DescribeAutoScalingGroupsResult().withAutoScalingGroups(List(
        desiredGroup,
        AutoScalingGroup("Role" -> "other", "Stage" -> "PROD"),
        AutoScalingGroup("Role" -> "example", "Stage" -> "TEST"),
        AutoScalingGroup("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "TEST"),
        AutoScalingGroup("Stack" -> "contentapi", "App" -> "elasticsearch", "Stage" -> "PROD"),
        AutoScalingGroup("Stack" -> "monkey", "App" -> "logcabin", "Stage" -> "PROD")
      ))

    val p = DeploymentPackage("example", Seq(App("logcabin")), Map.empty, "nowt much", new File("/tmp/packages/webapp"))
    asg.groupForAppAndStage(p, Stage("PROD"), NamedStack("contentapi")) should be (desiredGroup)
  }

  it should "find the first matching auto-scaling group with Stack and App tags" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val asg = new ASG {
      override def client(implicit keyRing: KeyRing) = asgClientMock
    }

    val desiredGroup = AutoScalingGroup("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "PROD")

    when (asgClientMock.describeAutoScalingGroups(any[DescribeAutoScalingGroupsRequest])) thenReturn
      new DescribeAutoScalingGroupsResult().withAutoScalingGroups(List(
        desiredGroup,
        AutoScalingGroup("Role" -> "other", "Stage" -> "PROD"),
        AutoScalingGroup("Role" -> "example", "Stage" -> "TEST"),
        AutoScalingGroup("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "TEST"),
        AutoScalingGroup("Stack" -> "contentapi", "App" -> "elasticsearch", "Stage" -> "PROD"),
        AutoScalingGroup("Stack" -> "monkey", "App" -> "logcabin", "Stage" -> "PROD")
      ))

    val p = DeploymentPackage("example", Seq(App("logcabin"), App("elasticsearch")), Map.empty, "nowt much", new File("/tmp/packages/webapp"))
    asg.groupForAppAndStage(p, Stage("PROD"), NamedStack("contentapi")) should be (desiredGroup)
  }

  it should "fail if more than one ASG matches the Stack and App tags" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val asg = new ASG {
      override def client(implicit keyRing: KeyRing) = asgClientMock
    }

    val desiredGroup = AutoScalingGroup("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "PROD", "Role" -> "monkey")

    when (asgClientMock.describeAutoScalingGroups(any[DescribeAutoScalingGroupsRequest])) thenReturn
      new DescribeAutoScalingGroupsResult().withAutoScalingGroups(List(
        desiredGroup,
        AutoScalingGroup("Role" -> "other", "Stage" -> "PROD"),
        AutoScalingGroup("Role" -> "example", "Stage" -> "TEST"),
        AutoScalingGroup("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "PROD", "Role" -> "orangutang"),
        AutoScalingGroup("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "TEST"),
        AutoScalingGroup("Stack" -> "contentapi", "App" -> "elasticsearch", "Stage" -> "PROD"),
        AutoScalingGroup("Stack" -> "monkey", "App" -> "logcabin", "Stage" -> "PROD")
      ))

    val p = DeploymentPackage("example", Seq(App("logcabin"), App("elasticsearch")), Map.empty, "nowt much", new File("/tmp/packages/webapp"))

    a [FailException] should be thrownBy {
      asg.groupForAppAndStage(p, Stage("PROD"), NamedStack("contentapi")) should be (desiredGroup)
    }
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

    val group = AutoScalingGroup("elb", "Role" -> "example", "Stage" -> "PROD").withDesiredCapacity(1)

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

    val group = AutoScalingGroup("Role" -> "example", "Stage" -> "PROD")
      .withDesiredCapacity(1).withInstances(new ASGInstance().withHealthStatus("Foobar"))

    when (elbClientMock.describeInstanceHealth(
      new DescribeInstanceHealthRequest().withLoadBalancerName("elb")
    )).thenReturn(new DescribeInstanceHealthResult().withInstanceStates(new InstanceState().withState("")))

    asg.isStabilized(group) should be (false)

    val updatedGroup = AutoScalingGroup("Role" -> "example", "Stage" -> "PROD")
      .withDesiredCapacity(1).withInstances(new ASGInstance().withLifecycleState(LifecycleState.InService))

    asg.isStabilized(updatedGroup) should be (true)
  }

  it should "find the first matching auto-scaling group with Stack and App tags, on the second page of results" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val asg = new ASG {
      override def client(implicit keyRing: KeyRing) = asgClientMock
    }

    val firstRequest = new DescribeAutoScalingGroupsRequest
    val secondRequest = new DescribeAutoScalingGroupsRequest().withNextToken("someToken")

    val desiredGroup = AutoScalingGroup("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "PROD")

    when (asgClientMock.describeAutoScalingGroups(firstRequest)) thenReturn
      new DescribeAutoScalingGroupsResult().withAutoScalingGroups(List(
        AutoScalingGroup("Role" -> "other", "Stage" -> "PROD"),
        AutoScalingGroup("Role" -> "example", "Stage" -> "TEST")
      )).withNextToken("someToken" +
        "")
    when (asgClientMock.describeAutoScalingGroups(secondRequest)) thenReturn
      new DescribeAutoScalingGroupsResult().withAutoScalingGroups(List(
        AutoScalingGroup("Stack" -> "contentapi", "App" -> "logcabin", "Stage" -> "TEST"),
        AutoScalingGroup("Stack" -> "contentapi", "App" -> "elasticsearch", "Stage" -> "PROD"),
        AutoScalingGroup("Stack" -> "monkey", "App" -> "logcabin", "Stage" -> "PROD"),
        desiredGroup
      ))

    val p = DeploymentPackage("example", Seq(App("logcabin"), App("elasticsearch")), Map.empty, "nowt much", new File("/tmp/packages/webapp"))
    asg.groupForAppAndStage(p, Stage("PROD"), NamedStack("contentapi")) should be (desiredGroup)
  }

  object AutoScalingGroup {
    def apply(tags: (String, String)*) = new AutoScalingGroup().withTags(tags map {
      case (key, value) => new TagDescription().withKey(key).withValue(value)
    })
    def apply(elbName: String, tags: (String, String)*) = new AutoScalingGroup().withTags(tags map {
      case (key, value) => new TagDescription().withKey(key).withValue(value)
    }).withLoadBalancerNames(elbName)
  }
}
