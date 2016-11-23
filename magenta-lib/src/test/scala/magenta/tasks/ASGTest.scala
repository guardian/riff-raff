package magenta.tasks

import java.util.UUID

import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.{Instance => ASGInstance, _}
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.{DescribeInstanceHealthRequest, DescribeInstanceHealthResult, InstanceState, Instance => ELBInstance}
import magenta.artifact.S3Path
import magenta.deployment_type.DeploymentType
import magenta.{App, KeyRing, Stage, _}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConversions._

class ASGTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  val deploymentTypes = Nil

  it should "find the matching auto-scaling group with App tagging" in {
    val asgClientMock = mock[AmazonAutoScalingClient]

    val desiredGroup = AutoScalingGroup("App" -> "example", "Stage" -> "PROD")

    when (asgClientMock.describeAutoScalingGroups(any[DescribeAutoScalingGroupsRequest])) thenReturn
      new DescribeAutoScalingGroupsResult().withAutoScalingGroups(List(
        desiredGroup,
        AutoScalingGroup("App" -> "other", "Stage" -> "PROD"),
        AutoScalingGroup("App" -> "example", "Stage" -> "TEST")
      ))

    val p = DeploymentPackage("example", Seq(App("app")), Map.empty, deploymentType = null,
      S3Path("artifact-bucket", "project/123/example"), true)
    ASG.groupForAppAndStage(p, Stage("PROD"), UnnamedStack, asgClientMock, reporter) should be (desiredGroup)
  }

  it should "find the matching auto-scaling group with Role tagging" in {
    val asgClientMock = mock[AmazonAutoScalingClient]

    val desiredGroup = AutoScalingGroup("Role" -> "example", "Stage" -> "PROD")

    when (asgClientMock.describeAutoScalingGroups(any[DescribeAutoScalingGroupsRequest])) thenReturn
      new DescribeAutoScalingGroupsResult().withAutoScalingGroups(List(
        desiredGroup,
        AutoScalingGroup(("Role" -> "other"), ("Stage" -> "PROD")),
        AutoScalingGroup(("Role" -> "example"), ("Stage" -> "TEST"))
      ))

    val p = DeploymentPackage("example", Seq(App("app")), Map.empty, deploymentType = null,
      S3Path("artifact-bucket", "project/123/example"), true)
    ASG.groupForAppAndStage(p, Stage("PROD"), UnnamedStack, asgClientMock, reporter) should be (desiredGroup)
  }

  it should "find the matching auto-scaling group with Stack and App tags" in {
    val asgClientMock = mock[AmazonAutoScalingClient]

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

    val p = DeploymentPackage("example", Seq(App("logcabin")), Map.empty, deploymentType = null,
      S3Path("artifact-bucket", "project/123/example"), true)
    ASG.groupForAppAndStage(p, Stage("PROD"), NamedStack("contentapi"), asgClientMock, reporter) should be (desiredGroup)
  }

  it should "find the first matching auto-scaling group with Stack and App tags" in {
    val asgClientMock = mock[AmazonAutoScalingClient]

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

    val p = DeploymentPackage("example", Seq(App("logcabin"), App("elasticsearch")), Map.empty, deploymentType = null,
      S3Path("artifact-bucket", "project/123/example"), true)
    ASG.groupForAppAndStage(p, Stage("PROD"), NamedStack("contentapi"), asgClientMock, reporter) should be (desiredGroup)
  }

  it should "fail if more than one ASG matches the Stack and App tags" in {
    val asgClientMock = mock[AmazonAutoScalingClient]

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

    val p = DeploymentPackage("example", Seq(App("logcabin"), App("elasticsearch")), Map.empty, deploymentType = null,
      S3Path("artifact-bucket", "project/123/example"), true)

    a [FailException] should be thrownBy {
      ASG.groupForAppAndStage(p, Stage("PROD"), NamedStack("contentapi"), asgClientMock, reporter) should be (desiredGroup)
    }
  }

  it should "wait for instances in ELB to stabilise if there is one" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val elbClientMock = mock[AmazonElasticLoadBalancingClient]

    val group = AutoScalingGroup("elb", "Role" -> "example", "Stage" -> "PROD").withDesiredCapacity(1)

    when (elbClientMock.describeInstanceHealth(
      new DescribeInstanceHealthRequest().withLoadBalancerName("elb")
    )).thenReturn(new DescribeInstanceHealthResult().withInstanceStates(new InstanceState().withState("")))

    ASG.isStabilized(group, asgClientMock, elbClientMock) shouldBe Left("Only 0 of 1 ELB instances InService")

    when (elbClientMock.describeInstanceHealth(
      new DescribeInstanceHealthRequest().withLoadBalancerName("elb")
    )).thenReturn(new DescribeInstanceHealthResult().withInstanceStates(new InstanceState().withState("InService")))

    ASG.isStabilized(group, asgClientMock, elbClientMock) shouldBe Right(())
  }

  it should "just check ASG health for stability if there is no ELB" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val elbClientMock = mock[AmazonElasticLoadBalancingClient]

    val group = AutoScalingGroup("Role" -> "example", "Stage" -> "PROD")
      .withDesiredCapacity(1).withInstances(new ASGInstance().withHealthStatus("Foobar"))

    when (elbClientMock.describeInstanceHealth(
      new DescribeInstanceHealthRequest().withLoadBalancerName("elb")
    )).thenReturn(new DescribeInstanceHealthResult().withInstanceStates(new InstanceState().withState("")))

    ASG.isStabilized(group, asgClientMock, elbClientMock) shouldBe Left("Only 0 of 1 instances are InService")

    val updatedGroup = AutoScalingGroup("Role" -> "example", "Stage" -> "PROD")
      .withDesiredCapacity(1).withInstances(new ASGInstance().withLifecycleState(LifecycleState.InService))

    ASG.isStabilized(updatedGroup, asgClientMock, elbClientMock) shouldBe Right(())
  }

  it should "find the first matching auto-scaling group with Stack and App tags, on the second page of results" in {
    val asgClientMock = mock[AmazonAutoScalingClient]

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

    val p = DeploymentPackage("example", Seq(App("logcabin"), App("elasticsearch")), Map.empty, deploymentType = null,
      S3Path("artifact-bucket", "project/123/example"), true)
    ASG.groupForAppAndStage(p, Stage("PROD"), NamedStack("contentapi"), asgClientMock, reporter) should be (desiredGroup)
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
