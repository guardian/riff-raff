package magenta.tasks

import java.nio.ByteBuffer

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.{AWSCredentialsProvider, AWSCredentialsProviderChain, BasicAWSCredentials}
import com.amazonaws.regions.{RegionUtils, Region => AwsRegion}
import com.amazonaws.retry.{PredefinedRetryPolicies, RetryPolicy}
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.{Instance => ASGInstance, Tag => _, _}
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.{CreateTagsRequest, DescribeInstancesRequest, Tag => EC2Tag}
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.{Instance => ELBInstance, _}
import com.amazonaws.services.lambda.AWSLambdaClient
import com.amazonaws.services.lambda.model.UpdateFunctionCodeRequest
import com.amazonaws.services.s3.AmazonS3Client
import magenta.{App, DeployReporter, DeploymentPackage, KeyRing, NamedStack, Region, Stack, Stage, UnnamedStack}

import scala.collection.JavaConversions._

object S3 extends AWS {
  def makeS3client(keyRing: KeyRing,
                   region: Region,
                   config: ClientConfiguration = clientConfiguration): AmazonS3Client =
    new AmazonS3Client(provider(keyRing), config).withRegion(awsRegion(region))
}

object Lambda extends AWS {
  def makeLambdaClient(keyRing: KeyRing, region: Region): AWSLambdaClient =
    new AWSLambdaClient(provider(keyRing), clientConfiguration).withRegion(awsRegion(region))

  def lambdaUpdateFunctionCodeRequest(functionName: String, buffer: ByteBuffer): UpdateFunctionCodeRequest = {
    val request = new UpdateFunctionCodeRequest
    request.withFunctionName(functionName)
    request.withZipFile(buffer)
    request
  }

  def lambdaUpdateFunctionCodeRequest(functionName: String,
                                      s3Bucket: String,
                                      s3Key: String): UpdateFunctionCodeRequest = {
    new UpdateFunctionCodeRequest().withFunctionName(functionName).withS3Bucket(s3Bucket).withS3Key(s3Key)
  }
}

object ASG extends AWS {
  def makeAsgClient(keyRing: KeyRing, region: Region): AmazonAutoScalingClient =
    new AmazonAutoScalingClient(provider(keyRing), clientConfiguration).withRegion(awsRegion(region))

  def desiredCapacity(name: String, capacity: Int, client: AmazonAutoScalingClient) =
    client.setDesiredCapacity(
      new SetDesiredCapacityRequest().withAutoScalingGroupName(name).withDesiredCapacity(capacity)
    )

  def maxCapacity(name: String, capacity: Int, client: AmazonAutoScalingClient) =
    client.updateAutoScalingGroup(
      new UpdateAutoScalingGroupRequest().withAutoScalingGroupName(name).withMaxSize(capacity))

  def isStabilized(asg: AutoScalingGroup,
                   asgClient: AmazonAutoScalingClient,
                   elbClient: AmazonElasticLoadBalancingClient) = {
    elbName(asg) match {
      case Some(name) => {
        val elbHealth = ELB.instanceHealth(name, elbClient)
        elbHealth.size == asg.getDesiredCapacity && elbHealth.forall(instance => instance.getState == "InService")
      }
      case None => {
        val instances = asg.getInstances
        instances.size == asg.getDesiredCapacity &&
        instances.forall(instance => instance.getLifecycleState == LifecycleState.InService.toString)
      }
    }
  }

  def elbName(asg: AutoScalingGroup) = asg.getLoadBalancerNames.headOption

  def cull(asg: AutoScalingGroup,
           instance: ASGInstance,
           asgClient: AmazonAutoScalingClient,
           elbClient: AmazonElasticLoadBalancingClient) = {
    elbName(asg) foreach (ELB.deregister(_, instance, elbClient))

    asgClient.terminateInstanceInAutoScalingGroup(
      new TerminateInstanceInAutoScalingGroupRequest()
        .withInstanceId(instance.getInstanceId)
        .withShouldDecrementDesiredCapacity(true)
    )
  }

  def refresh(asg: AutoScalingGroup, client: AmazonAutoScalingClient) =
    client
      .describeAutoScalingGroups(
        new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(asg.getAutoScalingGroupName)
      )
      .getAutoScalingGroups
      .head

  def suspendAlarmNotifications(name: String, client: AmazonAutoScalingClient) = client.suspendProcesses(
    new SuspendProcessesRequest().withAutoScalingGroupName(name).withScalingProcesses("AlarmNotification")
  )

  def resumeAlarmNotifications(name: String, client: AmazonAutoScalingClient) = client.resumeProcesses(
    new ResumeProcessesRequest().withAutoScalingGroupName(name).withScalingProcesses("AlarmNotification")
  )

  def groupForAppAndStage(pkg: DeploymentPackage,
                          stage: Stage,
                          stack: Stack,
                          client: AmazonAutoScalingClient,
                          reporter: DeployReporter): AutoScalingGroup = {
    case class ASGMatch(app: App, matches: List[AutoScalingGroup])

    implicit class RichAutoscalingGroup(asg: AutoScalingGroup) {
      def hasTag(key: String, value: String) = asg.getTags exists { tag =>
        tag.getKey == key && tag.getValue == value
      }
      def matchApp(app: App, stack: Stack): Boolean = {
        stack match {
          case UnnamedStack => hasTag("Role", pkg.name) || hasTag("App", pkg.name)
          case NamedStack(stackName) => hasTag("Stack", stackName) && hasTag("App", app.name)
        }
      }
    }

    def listAutoScalingGroups(nextToken: Option[String] = None): List[AutoScalingGroup] = {
      val request = new DescribeAutoScalingGroupsRequest()
      nextToken.foreach(request.setNextToken)
      val result = client.describeAutoScalingGroups(request)
      val autoScalingGroups = result.getAutoScalingGroups.toList
      Option(result.getNextToken) match {
        case None => autoScalingGroups
        case token: Some[String] => autoScalingGroups ++ listAutoScalingGroups(token)
      }
    }

    val groups = listAutoScalingGroups()
    val filteredByStage = groups filter { _.hasTag("Stage", stage.name) }
    val appToMatchingGroups = pkg.apps.flatMap { app =>
      val matches = filteredByStage.filter(_.matchApp(app, stack))
      if (matches.isEmpty) None else Some(ASGMatch(app, matches))
    }

    val appMatch: ASGMatch = appToMatchingGroups match {
      case Seq() =>
        reporter.fail(s"No autoscaling group found in ${stage.name} with tags matching package ${pkg.name}")
      case Seq(onlyMatch) => onlyMatch
      case firstMatch +: otherMatches =>
        reporter.info(s"More than one app matches an autoscaling group, the first in the list will be used")
        firstMatch
    }

    appMatch match {
      case ASGMatch(_, List(singleGroup)) =>
        reporter.verbose(s"Using group ${singleGroup.getAutoScalingGroupName}")
        singleGroup
      case ASGMatch(app, groupList) =>
        reporter.fail(
          s"More than one autoscaling group match for $app in ${stage.name} (${groupList.map(_.getAutoScalingGroupName).mkString(", ")}). Failing fast since this may be non-deterministic.")
    }
  }
}

object ELB extends AWS {
  def makeElbClient(keyRing: KeyRing, region: Region): AmazonElasticLoadBalancingClient =
    new AmazonElasticLoadBalancingClient(provider(keyRing), clientConfiguration).withRegion(awsRegion(region))

  def instanceHealth(elbName: String, client: AmazonElasticLoadBalancingClient) =
    client.describeInstanceHealth(new DescribeInstanceHealthRequest(elbName)).getInstanceStates

  def deregister(elbName: String, instance: ASGInstance, client: AmazonElasticLoadBalancingClient) =
    client.deregisterInstancesFromLoadBalancer(
      new DeregisterInstancesFromLoadBalancerRequest()
        .withLoadBalancerName(elbName)
        .withInstances(new ELBInstance().withInstanceId(instance.getInstanceId)))
}

object EC2 extends AWS {
  def makeEc2Client(keyRing: KeyRing, region: Region): AmazonEC2Client = {
    new AmazonEC2Client(provider(keyRing), clientConfiguration).withRegion(awsRegion(region))
  }

  def setTag(instances: List[ASGInstance], key: String, value: String, client: AmazonEC2Client) {
    val request =
      new CreateTagsRequest().withResources(instances map { _.getInstanceId }).withTags(new EC2Tag(key, value))

    client.createTags(request)
  }

  def hasTag(instance: ASGInstance, key: String, value: String, client: AmazonEC2Client): Boolean = {
    describe(instance, client).getTags exists { tag =>
      tag.getKey == key && tag.getValue == value
    }
  }

  def describe(instance: ASGInstance, client: AmazonEC2Client) =
    client
      .describeInstances(new DescribeInstancesRequest().withInstanceIds(instance.getInstanceId))
      .getReservations
      .flatMap(_.getInstances)
      .head

  def apply(instance: ASGInstance, client: AmazonEC2Client) = describe(instance, client)
}

trait AWS {
  lazy val accessKey = Option(System.getenv.get("aws_access_key")).getOrElse {
    sys.error("Cannot authenticate, 'aws_access_key' must be set as a system property")
  }
  lazy val secretAccessKey = Option(System.getenv.get("aws_secret_access_key")).getOrElse {
    sys.error("Cannot authenticate, aws_secret_access_key' must be set as a system property")
  }

  lazy val envCredentials = new BasicAWSCredentials(accessKey, secretAccessKey)

  def provider(keyRing: KeyRing): AWSCredentialsProvider = new AWSCredentialsProviderChain(
    new AWSCredentialsProvider {
      def refresh() {}
      def getCredentials =
        keyRing.apiCredentials.get("aws") map { credentials =>
          new BasicAWSCredentials(credentials.id, credentials.secret)
        } get
    },
    new AWSCredentialsProvider {
      def refresh() {}
      def getCredentials = envCredentials
    }
  )

  def awsRegion(region: Region): AwsRegion = RegionUtils.getRegion(region.name)

  val clientConfiguration = new ClientConfiguration().withRetryPolicy(
    new RetryPolicy(
      PredefinedRetryPolicies.DEFAULT_RETRY_CONDITION,
      PredefinedRetryPolicies.DEFAULT_BACKOFF_STRATEGY,
      20,
      false
    ))

  val clientConfigurationNoRetry = new ClientConfiguration().withRetryPolicy(PredefinedRetryPolicies.NO_RETRY_POLICY)
}
