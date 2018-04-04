package magenta.tasks

import java.util.UUID

import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.{Instance => ASGInstance, _}
import com.amazonaws.services.elasticloadbalancing.{AmazonElasticLoadBalancingClient => ClassicELBClient}
import com.amazonaws.services.elasticloadbalancingv2.{AmazonElasticLoadBalancingClient => ApplicationELBClient}
import com.amazonaws.services.elasticloadbalancing.model.{DescribeInstanceHealthRequest, DescribeInstanceHealthResult, InstanceState, Instance => ELBInstance}
import com.amazonaws.services.elasticloadbalancingv2.model.{TagDescription => _, _}
import magenta.artifact.S3Path
import magenta.deployment_type.DeploymentType
import magenta.{App, KeyRing, Stage, _}
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConversions._

class ASGTest extends FlatSpec with Matchers with MockitoSugar {
  implicit val fakeKeyRing = KeyRing()
  val reporter = DeployReporter.rootReporterFor(UUID.randomUUID(), fixtures.parameters())
  val deploymentTypes = Nil

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

    val p = DeploymentPackage("example", App("logcabin"), Map.empty, deploymentType = null,
      S3Path("artifact-bucket", "project/123/example"))
    ASG.groupForAppAndStage(p, Stage("PROD"), Stack("contentapi"), asgClientMock, reporter) should be (desiredGroup)
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

    val p = DeploymentPackage("example", App("logcabin"), Map.empty, deploymentType = null,
      S3Path("artifact-bucket", "project/123/example"))

    a [FailException] should be thrownBy {
      ASG.groupForAppAndStage(p, Stage("PROD"), Stack("contentapi"), asgClientMock, reporter) should be (desiredGroup)
    }
  }

  it should "wait for instances in ELB to stabilise if there is one" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val appELBClient = mock[ApplicationELBClient]
    val classicELBClient = mock[ClassicELBClient]

    val group = AutoScalingGroup("elb", "Role" -> "example", "Stage" -> "PROD").withDesiredCapacity(1)

    when (classicELBClient.describeInstanceHealth(
      new DescribeInstanceHealthRequest().withLoadBalancerName("elb")
    )).thenReturn(new DescribeInstanceHealthResult().withInstanceStates(new InstanceState().withState("")))

    ASG.isStabilized(group, asgClientMock, ELB.Client(classicELBClient, appELBClient)) shouldBe
      Left("Only 0 of 1 ELB instances InService")

    when (classicELBClient.describeInstanceHealth(
      new DescribeInstanceHealthRequest().withLoadBalancerName("elb")
    )).thenReturn(new DescribeInstanceHealthResult().withInstanceStates(new InstanceState().withState("InService")))

    ASG.isStabilized(group, asgClientMock, ELB.Client(classicELBClient, appELBClient)) shouldBe Right(())
  }

  it should "wait for instances in an ELB target group to be bealthy" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val appELBClient = mock[ApplicationELBClient]
    val classicELBClient = mock[ClassicELBClient]

    val group = AutoScalingGroup("Role" -> "example", "Stage" -> "PROD")
      .withTargetGroupARNs("elbTargetARN")
      .withDesiredCapacity(1)

    when (appELBClient.describeTargetHealth(
      new DescribeTargetHealthRequest().withTargetGroupArn("elbTargetARN")
    )).thenReturn(new DescribeTargetHealthResult().withTargetHealthDescriptions(
      new TargetHealthDescription().withTargetHealth(new TargetHealth().withState(TargetHealthStateEnum.Unhealthy))))

    ASG.isStabilized(group, asgClientMock, ELB.Client(classicELBClient, appELBClient)) shouldBe
      Left("Only 0 of 1 Application ELB instances healthy")

    when (appELBClient.describeTargetHealth(
      new DescribeTargetHealthRequest().withTargetGroupArn("elbTargetARN")
    )).thenReturn(new DescribeTargetHealthResult().withTargetHealthDescriptions(
      new TargetHealthDescription().withTargetHealth(new TargetHealth().withState(TargetHealthStateEnum.Healthy))))

    ASG.isStabilized(group, asgClientMock, ELB.Client(classicELBClient, appELBClient)) shouldBe Right(())
  }

  it should "just check ASG health for stability if there is no ELB" in {
    val asgClientMock = mock[AmazonAutoScalingClient]
    val appELBClient = mock[ApplicationELBClient]
    val classicELBClient = mock[ClassicELBClient]

    val group = AutoScalingGroup("Role" -> "example", "Stage" -> "PROD")
      .withDesiredCapacity(1).withInstances(new ASGInstance().withHealthStatus("Foobar"))

    when (classicELBClient.describeInstanceHealth(
      new DescribeInstanceHealthRequest().withLoadBalancerName("elb")
    )).thenReturn(new DescribeInstanceHealthResult().withInstanceStates(new InstanceState().withState("")))

    ASG.isStabilized(group, asgClientMock, ELB.Client(classicELBClient, appELBClient)) shouldBe
      Left("Only 0 of 1 instances InService")

    val updatedGroup = AutoScalingGroup("Role" -> "example", "Stage" -> "PROD")
      .withDesiredCapacity(1).withInstances(new ASGInstance().withLifecycleState(LifecycleState.InService))

    ASG.isStabilized(updatedGroup, asgClientMock, ELB.Client(classicELBClient, appELBClient)) shouldBe Right(())
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

    val p = DeploymentPackage("example", App("logcabin"), Map.empty, deploymentType = null,
      S3Path("artifact-bucket", "project/123/example"))
    ASG.groupForAppAndStage(p, Stage("PROD"), Stack("contentapi"), asgClientMock, reporter) should be (desiredGroup)
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
