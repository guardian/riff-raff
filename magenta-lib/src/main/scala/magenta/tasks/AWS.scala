package magenta.tasks

import java.nio.ByteBuffer

import com.amazonaws.{AmazonClientException, AmazonWebServiceRequest, ClientConfiguration}
import com.amazonaws.auth.{AWSCredentialsProvider, AWSCredentialsProviderChain, BasicAWSCredentials}
import com.amazonaws.regions.{RegionUtils, Region => AwsRegion}
import com.amazonaws.retry.PredefinedRetryPolicies.SDKDefaultRetryCondition
import com.amazonaws.retry.{PredefinedRetryPolicies, RetryPolicy}
import com.amazonaws.services.autoscaling.{AmazonAutoScaling, AmazonAutoScalingClientBuilder}
import com.amazonaws.services.autoscaling.model.{Instance => ASGInstance, _}
import com.amazonaws.services.cloudformation.{AmazonCloudFormation, AmazonCloudFormationClientBuilder}
import com.amazonaws.services.cloudformation.model.{Stack => AmazonStack, Tag => CfnTag, _}
import com.amazonaws.services.ec2.{AmazonEC2, AmazonEC2ClientBuilder}
import com.amazonaws.services.ec2.model.{CreateTagsRequest, DescribeInstancesRequest, Tag => EC2Tag}
import com.amazonaws.services.elasticloadbalancing.{AmazonElasticLoadBalancing => ClassicELB, AmazonElasticLoadBalancingClientBuilder => ClassicELBBuilder}
import com.amazonaws.services.elasticloadbalancingv2.{AmazonElasticLoadBalancing => ApplicationELB, AmazonElasticLoadBalancingClientBuilder => ApplicationELBBuilder}
import com.amazonaws.services.elasticloadbalancing.model.{Instance => ELBInstance, _}
import com.amazonaws.services.elasticloadbalancingv2.model.{Tag => _, _}
import com.amazonaws.services.lambda.{AWSLambda, AWSLambdaClientBuilder}
import com.amazonaws.services.lambda.model.UpdateFunctionCodeRequest
import com.amazonaws.services.s3.model.{BucketLifecycleConfiguration, CreateBucketRequest}
import com.amazonaws.services.s3.model.BucketLifecycleConfiguration.Rule
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.amazonaws.services.securitytoken.{AWSSecurityTokenService, AWSSecurityTokenServiceClientBuilder}
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest
import com.gu.management.Loggable
import magenta.{App, DeployReporter, DeploymentPackage, KeyRing, NamedStack, Region, Stack, Stage, UnnamedStack}

import scala.annotation.tailrec
import scala.collection.JavaConverters._

object S3 extends AWS {
  def makeS3client(keyRing: KeyRing, region: Region, config: ClientConfiguration = clientConfiguration): AmazonS3 =
    AmazonS3ClientBuilder.standard()
      .withCredentials(provider(keyRing))
      .withClientConfiguration(config)
      .withRegion(region.name)
      .build()

  /**
    * Check (and if necessary create) that an S3 bucket exists for the account and region. Note that it is assumed that
    * the clients and region provided all match up - i.e. they are all configured to the same region and use the same
    * credentials.
    * @param prefix The prefix for the bucket. The resulting bucket will be <prefix>-<accountNumber>-<region>.
    * @param s3Client S3 client used to check and create the bucket and set a lifecycle policy if deleteAfterDays is set
    * @param stsClient The STS client that is used to retrieve the account number
    * @param region The region in which the bucket should be created
    * @param deleteAfterDays If set then if this bucket is created then a lifecycle policy rule is created to delete
    *                        objects uploaded to this bucket after the number of days specified
    *
    * @return
    */
  def accountSpecificBucket(prefix: String, s3Client: AmazonS3, stsClient: AWSSecurityTokenService,
    region: Region, reporter: DeployReporter, deleteAfterDays: Option[Int] = None): String = {
    val callerIdentityResponse = stsClient.getCallerIdentity(new GetCallerIdentityRequest())
    val accountNumber = callerIdentityResponse.getAccount
    val bucketName = s"$prefix-$accountNumber-${region.name}"
    if (!s3Client.doesBucketExist(bucketName)) {
      reporter.info(s"Creating bucket for this account and region: $bucketName ${region.name}")
      val createBucketRequest = region.name match {
        case "us-east-1" => new CreateBucketRequest(bucketName) // this needs to be special cased as setting this explicitly blows up
        case otherRegion => new CreateBucketRequest(bucketName, otherRegion)
      }
      s3Client.createBucket(createBucketRequest)
      deleteAfterDays.foreach { days =>
        val daysString = s"$days day${if(days==1) "" else "s"}"
        reporter.info(s"Creating lifecycle rule on bucket that deletes objects after $daysString")
        s3Client.setBucketLifecycleConfiguration(
          bucketName,
          new BucketLifecycleConfiguration().withRules(
            new Rule()
              .withId(s"Rule to delete objects after $daysString")
              .withStatus(BucketLifecycleConfiguration.ENABLED)
              .withExpirationInDays(days)
          )
        )
      }
    }
    bucketName
  }
}

object Lambda extends AWS {
  def makeLambdaClient(keyRing: KeyRing, region: Region): AWSLambda =
    AWSLambdaClientBuilder.standard()
    .withCredentials(provider(keyRing))
    .withClientConfiguration(clientConfiguration)
    .withRegion(region.name)
    .build()

  def lambdaUpdateFunctionCodeRequest(functionName: String, buffer: ByteBuffer): UpdateFunctionCodeRequest = {
    val request = new UpdateFunctionCodeRequest
    request.withFunctionName(functionName)
    request.withZipFile(buffer)
    request
  }

  def lambdaUpdateFunctionCodeRequest(functionName: String, s3Bucket: String, s3Key: String): UpdateFunctionCodeRequest = {
    new UpdateFunctionCodeRequest()
      .withFunctionName(functionName)
      .withS3Bucket(s3Bucket)
      .withS3Key(s3Key)
  }
}

object ASG extends AWS {
  def makeAsgClient(keyRing: KeyRing, region: Region): AmazonAutoScaling =
    AmazonAutoScalingClientBuilder.standard()
      .withCredentials(provider(keyRing))
      .withClientConfiguration(clientConfiguration)
      .withRegion(region.name)
      .build()

  def desiredCapacity(name: String, capacity: Int, client: AmazonAutoScaling) =
    client.setDesiredCapacity(
      new SetDesiredCapacityRequest().withAutoScalingGroupName(name).withDesiredCapacity(capacity)
    )

  def maxCapacity(name: String, capacity: Int, client: AmazonAutoScaling) =
    client.updateAutoScalingGroup(
      new UpdateAutoScalingGroupRequest().withAutoScalingGroupName(name).withMaxSize(capacity))

  def isStabilized(asg: AutoScalingGroup, asgClient: AmazonAutoScaling,
    elbClient: ELB.Client): Either[String, Unit] = {

    def matchCapacityAndState(states: List[String], desiredState: String, checkDescription: Option[String]): Either[String, Unit] = {
      val descriptionWithPrecedingSpace = checkDescription.map(d => s" $d").getOrElse("")
      if (states.size != asg.getDesiredCapacity)
        Left(s"Number of$descriptionWithPrecedingSpace instances (${states.size}) and ASG desired capacity (${asg.getDesiredCapacity}) don't match")
      else if (!states.forall(_ == desiredState))
        Left(s"Only ${states.count(_ == desiredState)} of ${states.size}$descriptionWithPrecedingSpace instances $desiredState")
      else
        Right(())
    }

    (elbName(asg), elbTargetArn(asg)) match {
      case (Some(name), None) => {
        val elbHealth = ELB.instanceHealth(name, elbClient.classic)
        matchCapacityAndState(elbHealth, "InService", Some("ELB"))
      }
      case (None, Some(arn)) => {
        val elbHealth = ELB.targetInstancesHealth(arn, elbClient.application)
        matchCapacityAndState(elbHealth, TargetHealthStateEnum.Healthy.toString, Some("Application ELB"))
      }
      case (None, None) => {
        val instanceStates = asg.getInstances.asScala.toList.map(_.getLifecycleState)
        matchCapacityAndState(instanceStates, LifecycleState.InService.toString, None)
      }
      case (Some(_), Some(_)) => Left(
        "Don't know how to check for stability of an ASG associated with a classic and application load balancer")
    }
  }

  def elbName(asg: AutoScalingGroup) = asg.getLoadBalancerNames.asScala.headOption

  def elbTargetArn(asg: AutoScalingGroup) = asg.getTargetGroupARNs.asScala.headOption

  def cull(asg: AutoScalingGroup, instance: ASGInstance, asgClient: AmazonAutoScaling, elbClient: ELB.Client) = {
    ELB.deregister(elbName(asg), elbTargetArn(asg), instance, elbClient)

    asgClient.terminateInstanceInAutoScalingGroup(
      new TerminateInstanceInAutoScalingGroupRequest()
        .withInstanceId(instance.getInstanceId).withShouldDecrementDesiredCapacity(true)
    )
  }

  def refresh(asg: AutoScalingGroup, client: AmazonAutoScaling) =
    client.describeAutoScalingGroups(
      new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(asg.getAutoScalingGroupName)
    ).getAutoScalingGroups.asScala.head

  def suspendAlarmNotifications(name: String, client: AmazonAutoScaling) = client.suspendProcesses(
    new SuspendProcessesRequest().withAutoScalingGroupName(name).withScalingProcesses("AlarmNotification")
  )

  def resumeAlarmNotifications(name: String, client: AmazonAutoScaling) = client.resumeProcesses(
    new ResumeProcessesRequest().withAutoScalingGroupName(name).withScalingProcesses("AlarmNotification")
  )

  def groupForAppAndStage(pkg: DeploymentPackage, stage: Stage, stack: Stack, client: AmazonAutoScaling,
    reporter: DeployReporter): AutoScalingGroup = {
    case class ASGMatch(app:App, matches:List[AutoScalingGroup])

    implicit class RichAutoscalingGroup(asg: AutoScalingGroup) {
      def hasTag(key: String, value: String) = asg.getTags.asScala exists { tag =>
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
      val autoScalingGroups = result.getAutoScalingGroups.asScala.toList
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

    val appMatch:ASGMatch = appToMatchingGroups match {
      case Seq() =>
        reporter.fail(s"No autoscaling group found in ${stage.name} with tags matching package ${pkg.name}")
      case Seq(onlyMatch) => onlyMatch
      case firstMatch +: otherMatches =>
        reporter.info(s"More than one app matches an autoscaling group, the first in the list will be used")
        firstMatch
    }

    appMatch match {
      case ASGMatch(_, List(singleGroup)) =>
        reporter.verbose(s"Using group ${singleGroup.getAutoScalingGroupName} (${singleGroup.getAutoScalingGroupARN})")
        singleGroup
      case ASGMatch(app, groupList) =>
        reporter.fail(s"More than one autoscaling group match for $app in ${stage.name} (${groupList.map(_.getAutoScalingGroupARN).mkString(", ")}). Failing fast since this may be non-deterministic.")
    }
  }
}

object ELB extends AWS {

  case class Client(classic: ClassicELB, application: ApplicationELB)

  def client(keyRing: KeyRing, region: Region): Client =
    Client(classicClient(keyRing, region), applicationClient(keyRing, region))

  def classicClient(keyRing: KeyRing, region: Region): ClassicELB =
    ClassicELBBuilder.standard()
      .withCredentials(provider(keyRing))
      .withClientConfiguration(clientConfiguration)
      .withRegion(region.name)
      .build()

  def applicationClient(keyRing: KeyRing, region: Region): ApplicationELB =
    ApplicationELBBuilder.standard()
      .withCredentials(provider(keyRing))
      .withClientConfiguration(clientConfiguration)
      .withRegion(region.name)
      .build()

  def targetInstancesHealth(targetARN: String, client: ApplicationELB): List[String] =
    client.describeTargetHealth(new DescribeTargetHealthRequest().withTargetGroupArn(targetARN))
      .getTargetHealthDescriptions.asScala.toList.map(_.getTargetHealth.getState)

  def instanceHealth(elbName: String, client: ClassicELB): List[String] =
    client.describeInstanceHealth(new DescribeInstanceHealthRequest(elbName))
      .getInstanceStates.asScala.toList.map(_.getState)

  def deregister(elbName: Option[String], elbTargetARN: Option[String], instance: ASGInstance, client: Client) = {
    elbName.foreach(name =>
      client.classic.deregisterInstancesFromLoadBalancer(
        new DeregisterInstancesFromLoadBalancerRequest().withLoadBalancerName(name)
          .withInstances(new ELBInstance().withInstanceId(instance.getInstanceId))))
    elbTargetARN.foreach(arn =>
      client.application.deregisterTargets(
        new DeregisterTargetsRequest().withTargetGroupArn(arn)
          .withTargets(new TargetDescription().withId(instance.getInstanceId))))
  }
}

object EC2 extends AWS {
  def makeEc2Client(keyRing: KeyRing, region: Region): AmazonEC2 = {
    AmazonEC2ClientBuilder.standard()
      .withCredentials(provider(keyRing))
      .withClientConfiguration(clientConfiguration)
      .withRegion(region.name)
      .build()
  }

  def setTag(instances: List[ASGInstance], key: String, value: String, client: AmazonEC2) {
    val request = new CreateTagsRequest().
      withResources(instances map { _.getInstanceId } asJavaCollection).
      withTags(new EC2Tag(key, value))

    client.createTags(request)
  }

  def hasTag(instance: ASGInstance, key: String, value: String, client: AmazonEC2): Boolean = {
    describe(instance, client).getTags.asScala exists { tag =>
      tag.getKey == key && tag.getValue == value
    }
  }

  def describe(instance: ASGInstance, client: AmazonEC2) = client.describeInstances(
    new DescribeInstancesRequest().withInstanceIds(instance.getInstanceId)
  ).getReservations.asScala.flatMap(_.getInstances.asScala).head

  def apply(instance: ASGInstance, client: AmazonEC2) = describe(instance, client)
}

object CloudFormation extends AWS {
  sealed trait ParameterValue
  case class SpecifiedValue(value: String) extends ParameterValue
  case object UseExistingValue extends ParameterValue

  sealed trait Template
  case class TemplateBody(body: String) extends Template
  case class TemplateUrl(url: String) extends Template

  val CAPABILITY_IAM = "CAPABILITY_IAM"

  def makeCfnClient(keyRing: KeyRing, region: Region): AmazonCloudFormation = {
    AmazonCloudFormationClientBuilder.standard()
      .withCredentials(provider(keyRing))
      .withClientConfiguration(clientConfiguration)
      .withRegion(region.name)
      .build()
  }

  def validateTemplate(template: Template, client: AmazonCloudFormation) = {
    val request = template match {
      case TemplateBody(body) => new ValidateTemplateRequest().withTemplateBody(body)
      case TemplateUrl(url) => new ValidateTemplateRequest().withTemplateURL(url)
    }
    client.validateTemplate(request)
  }

  def updateStack(name: String, template: Template, parameters: Map[String, ParameterValue],
    client: AmazonCloudFormation) = {

    val request = new UpdateStackRequest().withStackName(name).withCapabilities(CAPABILITY_IAM).withParameters(
      parameters map {
        case (k, SpecifiedValue(v)) => new Parameter().withParameterKey(k).withParameterValue(v)
        case (k, UseExistingValue) => new Parameter().withParameterKey(k).withUsePreviousValue(true)
      } toSeq: _*
    )
    val requestWithTemplate = template match {
      case TemplateBody(body) => request.withTemplateBody(body)
      case TemplateUrl(url) => request.withTemplateURL(url)
    }
    client.updateStack(requestWithTemplate)
  }

  def updateStackParams(name: String, parameters: Map[String, ParameterValue], client: AmazonCloudFormation) =
    client.updateStack(
      new UpdateStackRequest()
        .withStackName(name)
        .withCapabilities(CAPABILITY_IAM)
        .withUsePreviousTemplate(true)
        .withParameters(
          parameters map {
            case (k, SpecifiedValue(v)) => new Parameter().withParameterKey(k).withParameterValue(v)
            case (k, UseExistingValue) => new Parameter().withParameterKey(k).withUsePreviousValue(true)
          } toSeq: _*
        )
    )

  def createStack(reporter: DeployReporter, name: String, maybeTags: Option[Map[String, String]], template: Template, parameters: Map[String, ParameterValue],
    client: AmazonCloudFormation) = {

    val request = new CreateStackRequest()
      .withStackName(name)
      .withCapabilities(CAPABILITY_IAM)
      .withParameters(
        parameters map {
          case (k, SpecifiedValue(v)) => new Parameter().withParameterKey(k).withParameterValue(v)
          case (k, UseExistingValue) => reporter.fail(s"Missing parameter value for parameter $k: all must be specified when creating a stack. Subsequent updates will reuse existing parameter values where possible.")
        } toSeq: _*
      )

    val requestWithTemplate = template match {
      case TemplateBody(body) => request.withTemplateBody(body)
      case TemplateUrl(url) => request.withTemplateURL(url)
    }

    maybeTags.foreach { tags =>
      val sdkTags = tags.map{ case (key, value) => new CfnTag().withKey(key).withValue(value) }
      request.setTags(sdkTags.asJavaCollection)
    }

    client.createStack(requestWithTemplate)
  }

  def describeStack(name: String, client: AmazonCloudFormation) =
    client.describeStacks(
      new DescribeStacksRequest()
    ).getStacks.asScala.find(_.getStackName == name)

  def describeStackEvents(name: String, client: AmazonCloudFormation) =
    client.describeStackEvents(
      new DescribeStackEventsRequest().withStackName(name)
    )

  def findStackByTags(tags: Map[String, String], reporter: DeployReporter, client: AmazonCloudFormation): Option[AmazonStack] = {

    def tagsMatch(stack: AmazonStack): Boolean =
      tags.forall { case (key, value) => stack.getTags.asScala.exists(t => t.getKey == key && t.getValue == value) }

    @tailrec
    def recur(nextToken: Option[String] = None, existingStacks: List[AmazonStack] = Nil): List[AmazonStack] = {
      val request = new DescribeStacksRequest().withNextToken(nextToken.orNull)
      val response = client.describeStacks(request)

      val stacks = response.getStacks.asScala.foldLeft(existingStacks) {
        case (agg, stack) if tagsMatch(stack) => stack :: agg
        case (agg, _) => agg
      }

      Option(response.getNextToken) match {
        case None => stacks
        case token => recur(token, stacks)
      }
    }

    recur() match {
      case cfnStack :: Nil =>
        reporter.verbose(s"Found stack ${cfnStack.getStackName} (${cfnStack.getStackId})")
        Some(cfnStack)
      case Nil =>
        None
      case multipleStacks =>
        reporter.fail(s"More than one cloudformation stack match for $tags (matched ${multipleStacks.map(_.getStackName).mkString(", ")}). Failing fast since this may be non-deterministic.")
    }
  }
}

object STS extends AWS {
  def makeSTSclient(keyRing: KeyRing, region: Region): AWSSecurityTokenService = {
    AWSSecurityTokenServiceClientBuilder.standard()
      .withCredentials(provider(keyRing))
      .withClientConfiguration(clientConfiguration)
      .withRegion(region.name)
      .build()
  }
}

trait AWS extends Loggable {
  lazy val accessKey = Option(System.getenv.get("aws_access_key")).getOrElse{
    sys.error("Cannot authenticate, 'aws_access_key' must be set as a system property")
  }
  lazy val secretAccessKey = Option(System.getenv.get("aws_secret_access_key")).getOrElse{
    sys.error("Cannot authenticate, aws_secret_access_key' must be set as a system property")
  }

  lazy val envCredentials = new BasicAWSCredentials(accessKey, secretAccessKey)

  def provider(keyRing: KeyRing): AWSCredentialsProvider = new AWSCredentialsProviderChain(
    new AWSCredentialsProvider {
      def refresh() {}
      def getCredentials = keyRing.apiCredentials.get("aws") map {
          credentials => new BasicAWSCredentials(credentials.id,credentials.secret)
      } get
    },
    new AWSCredentialsProvider {
      def refresh() {}
      def getCredentials = envCredentials
    }
  )

  /* A retry condition that logs errors */
  class LoggingRetryCondition extends SDKDefaultRetryCondition {
    private def exceptionInfo(e: Throwable): String = {
      s"${e.getClass.getName} ${e.getMessage} Cause: ${Option(e.getCause).map(e => exceptionInfo(e))}"
    }

    override def shouldRetry(originalRequest: AmazonWebServiceRequest, exception: AmazonClientException, retriesAttempted: Int): Boolean = {
      val willRetry = super.shouldRetry(originalRequest, exception, retriesAttempted)
      if (willRetry) {
        logger.warn(s"AWS SDK retry $retriesAttempted: ${originalRequest.getClass.getName} threw ${exceptionInfo(exception)}")
      } else {
        logger.warn(s"Encounted fatal exception during AWS API call", exception)
        Option(exception.getCause).foreach(t => logger.warn(s"Cause of fatal exception", t))
      }
      willRetry
    }
  }
  val clientConfiguration = new ClientConfiguration().
    withRetryPolicy(new RetryPolicy(
      new LoggingRetryCondition(),
      PredefinedRetryPolicies.DEFAULT_BACKOFF_STRATEGY,
      20,
      false
    ))

  val clientConfigurationNoRetry = new ClientConfiguration().withRetryPolicy(PredefinedRetryPolicies.NO_RETRY_POLICY)
}
