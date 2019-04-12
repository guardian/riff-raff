package magenta.tasks

import java.nio.ByteBuffer

import cats.implicits._
import com.gu.management.Loggable
import magenta.{App, DeployReporter, DeploymentPackage, KeyRing, Region, Stack, Stage}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsCredentials, AwsCredentialsProvider, AwsCredentialsProviderChain}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy
import software.amazon.awssdk.core.retry.conditions.RetryCondition
import software.amazon.awssdk.core.retry.{RetryPolicy, RetryPolicyContext}
import software.amazon.awssdk.services.autoscaling.AutoScalingClient
import software.amazon.awssdk.services.autoscaling.model.{Instance => ASGInstance, _}
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.{Stack => AmazonStack, Tag => CfnTag, _}
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.{CreateTagsRequest, DescribeInstancesRequest, Tag => EC2Tag}
import software.amazon.awssdk.services.elasticloadbalancing.model.{DeregisterInstancesFromLoadBalancerRequest, DescribeInstanceHealthRequest, Instance => ELBInstance}
import software.amazon.awssdk.services.elasticloadbalancing.{ElasticLoadBalancingClient => ClassicELB}
import software.amazon.awssdk.services.elasticloadbalancingv2.model.{DeregisterTargetsRequest, DescribeTargetHealthRequest, TargetDescription, TargetHealthStateEnum}
import software.amazon.awssdk.services.elasticloadbalancingv2.{ElasticLoadBalancingV2Client => ApplicationELB}
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.UpdateFunctionCodeRequest
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model._
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest

import scala.annotation.tailrec
import scala.collection.JavaConverters._

object S3 extends AWS {
  def makeS3client(keyRing: KeyRing, region: Region, config: ClientOverrideConfiguration = clientConfiguration): S3Client =
    S3Client.builder()
      .region(region.awsRegion)
      .credentialsProvider(provider(keyRing))
      .overrideConfiguration(config)
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
  def accountSpecificBucket(prefix: String, s3Client: S3Client, stsClient: StsClient,
    region: Region, reporter: DeployReporter, deleteAfterDays: Option[Int] = None): String = {
    val accountNumber = STS.getAccountNumber(stsClient)
    val bucketName = s"$prefix-$accountNumber-${region.name}"
    if (!s3Client.listBuckets.buckets.asScala.exists(_.name == bucketName)) {
      reporter.info(s"Creating bucket for this account and region: $bucketName ${region.name}")

      val createBucketRequest = CreateBucketRequest.builder().bucket(bucketName).build()
      s3Client.createBucket(createBucketRequest)

      deleteAfterDays.foreach { days =>
        val daysString = s"$days day${if(days==1) "" else "s"}"
        reporter.info(s"Creating lifecycle rule on bucket that deletes objects after $daysString")
        s3Client.putBucketLifecycleConfiguration(
          PutBucketLifecycleConfigurationRequest.builder().bucket(bucketName).lifecycleConfiguration(
            BucketLifecycleConfiguration.builder()
              .rules(
                LifecycleRule.builder()
                  .id(s"Rule to delete objects after $daysString")
                  .status(ExpirationStatus.ENABLED)
                  .expiration(LifecycleExpiration.builder().days(days).build())
                  .build())
                .build())
            .build()
        )
      }
    }
    bucketName
  }
}

object Lambda extends AWS {
  def makeLambdaClient(keyRing: KeyRing, region: Region): LambdaClient =
    LambdaClient.builder()
    .credentialsProvider(provider(keyRing))
    .overrideConfiguration(clientConfiguration)
    .region(region.awsRegion)
    .build()

  def lambdaUpdateFunctionCodeRequest(functionName: String, buffer: ByteBuffer): UpdateFunctionCodeRequest =
    UpdateFunctionCodeRequest.builder().functionName(functionName).zipFile(SdkBytes.fromByteBuffer(buffer)).build()

  def lambdaUpdateFunctionCodeRequest(functionName: String, s3Bucket: String, s3Key: String): UpdateFunctionCodeRequest =
    UpdateFunctionCodeRequest.builder()
      .functionName(functionName)
      .s3Bucket(s3Bucket)
      .s3Key(s3Key)
      .build()
}

object ASG extends AWS {
  def makeAsgClient(keyRing: KeyRing, region: Region): AutoScalingClient =
    AutoScalingClient.builder()
      .credentialsProvider(provider(keyRing))
      .overrideConfiguration(clientConfiguration)
      .region(region.awsRegion)
      .build()

  def desiredCapacity(name: String, capacity: Int, client: AutoScalingClient) =
    client.setDesiredCapacity(
      SetDesiredCapacityRequest.builder().autoScalingGroupName(name).desiredCapacity(capacity).build()
    )

  def maxCapacity(name: String, capacity: Int, client: AutoScalingClient) =
    client.updateAutoScalingGroup(
      UpdateAutoScalingGroupRequest.builder().autoScalingGroupName(name).maxSize(capacity).build()
    )

  /**
    * Check status of all ELBs, or all instance states if there are no ELBs
    */
  def isStabilized(asg: AutoScalingGroup, elbClient: ELB.Client): Either[String, Unit] = {

    def matchCapacityAndState(states: List[String], desiredState: String, checkDescription: Option[String]): Either[String, Unit] = {
      val descriptionWithPrecedingSpace = checkDescription.map(d => s" $d").getOrElse("")
      if (states.size != asg.desiredCapacity)
        Left(s"Number of$descriptionWithPrecedingSpace instances (${states.size}) and ASG desired capacity (${asg.desiredCapacity}) don't match")
      else if (!states.forall(_ == desiredState))
        Left(s"Only ${states.count(_ == desiredState)} of ${states.size}$descriptionWithPrecedingSpace instances $desiredState")
      else
        Right(())
    }

    def checkClassicELB(name: String): Either[String, Unit] = {
      val elbHealth = ELB.instanceHealth(name, elbClient.classic)
      matchCapacityAndState(elbHealth, "InService", Some("Classic ELB"))
    }

    def checkTargetGroup(arn: String): Either[String, Unit] = {
      val elbHealth = ELB.targetInstancesHealth(arn, elbClient.application)
      matchCapacityAndState(elbHealth, TargetHealthStateEnum.HEALTHY.toString, Some("V2 ELB"))
    }

    val classicElbNames = elbNames(asg)
    val targetGroupArns = elbTargetArns(asg)

    if (classicElbNames.nonEmpty || targetGroupArns.nonEmpty) {
      for {
        _ <- classicElbNames.map(checkClassicELB).sequence
        _ <- targetGroupArns.map(checkTargetGroup).sequence
      } yield ()
    } else {
      val instanceStates = asg.instances.asScala.toList.map(_.lifecycleStateAsString)
      matchCapacityAndState(instanceStates, LifecycleState.IN_SERVICE.toString, None)
    }
  }

  def elbNames(asg: AutoScalingGroup): List[String] = asg.loadBalancerNames.asScala.toList

  def elbTargetArns(asg: AutoScalingGroup): List[String] = asg.targetGroupARNs.asScala.toList

  def cull(asg: AutoScalingGroup, instance: ASGInstance, asgClient: AutoScalingClient, elbClient: ELB.Client): TerminateInstanceInAutoScalingGroupResponse = {
    ELB.deregister(elbNames(asg), elbTargetArns(asg), instance, elbClient)

    asgClient.terminateInstanceInAutoScalingGroup(
      TerminateInstanceInAutoScalingGroupRequest.builder()
        .instanceId(instance.instanceId)
        .shouldDecrementDesiredCapacity(true)
        .build()
    )
  }

  def refresh(asg: AutoScalingGroup, client: AutoScalingClient): AutoScalingGroup =
    client.describeAutoScalingGroups(
      DescribeAutoScalingGroupsRequest.builder().autoScalingGroupNames(asg.autoScalingGroupName()).build()
    ).autoScalingGroups.asScala.head

  def suspendAlarmNotifications(name: String, client: AutoScalingClient): SuspendProcessesResponse =
    client.suspendProcesses(
      SuspendProcessesRequest.builder().autoScalingGroupName(name).scalingProcesses("AlarmNotification").build()
    )

  def resumeAlarmNotifications(name: String, client: AutoScalingClient): ResumeProcessesResponse =
    client.resumeProcesses(
      ResumeProcessesRequest.builder().autoScalingGroupName(name).scalingProcesses("AlarmNotification").build()
    )

  def groupForAppAndStage(pkg: DeploymentPackage, stage: Stage, stack: Stack, client: AutoScalingClient, reporter: DeployReporter): AutoScalingGroup = {
    case class ASGMatch(app:App, matches:List[AutoScalingGroup])

    implicit class RichAutoscalingGroup(asg: AutoScalingGroup) {
      def hasTag(key: String, value: String): Boolean = asg.tags.asScala.exists { tag =>
        tag.key == key && tag.value == value
      }
      def matchApp(app: App, stack: Stack): Boolean = hasTag("Stack", stack.name) && hasTag("App", app.name)
    }

    def listAutoScalingGroups(nextToken: Option[String] = None): List[AutoScalingGroup] = {
      val request = DescribeAutoScalingGroupsRequest.builder()
      nextToken.foreach(request.nextToken)
      val result = client.describeAutoScalingGroups(request.build())
      val autoScalingGroups = result.autoScalingGroups.asScala.toList
      Option(result.nextToken) match {
        case None => autoScalingGroups
        case token: Some[String] => autoScalingGroups ++ listAutoScalingGroups(token)
      }
    }

    val groups = listAutoScalingGroups()
    val filteredByStage = groups filter { _.hasTag("Stage", stage.name) }
    val appToMatchingGroups = {
      val matches = filteredByStage.filter(_.matchApp(pkg.app, stack))
      if (matches.isEmpty) None else Some(ASGMatch(pkg.app, matches))
    }

    appToMatchingGroups match {
      case None =>
        reporter.fail(s"No autoscaling group found in ${stage.name} with tags matching package ${pkg.name}")
      case Some(ASGMatch(_, List(singleGroup))) =>
        reporter.verbose(s"Using group ${singleGroup.autoScalingGroupName} (${singleGroup.autoScalingGroupARN})")
        singleGroup
      case Some(ASGMatch(app, groupList)) =>
        reporter.fail(s"More than one autoscaling group match for $app in ${stage.name} (${groupList.map(_.autoScalingGroupARN).mkString(", ")}). Failing fast since this may be non-deterministic.")
    }
  }
}

object ELB extends AWS {

  case class Client(classic: ClassicELB, application: ApplicationELB)

  def client(keyRing: KeyRing, region: Region): Client =
    Client(classicClient(keyRing, region), applicationClient(keyRing, region))

  def classicClient(keyRing: KeyRing, region: Region): ClassicELB =
    ClassicELB.builder()
      .credentialsProvider(provider(keyRing))
      .overrideConfiguration(clientConfiguration)
      .region(region.awsRegion)
      .build()

  def applicationClient(keyRing: KeyRing, region: Region): ApplicationELB =
    ApplicationELB.builder()
      .credentialsProvider(provider(keyRing))
      .overrideConfiguration(clientConfiguration)
      .region(region.awsRegion)
      .build()

  def targetInstancesHealth(targetARN: String, client: ApplicationELB): List[String] =
    client.describeTargetHealth(DescribeTargetHealthRequest.builder().targetGroupArn(targetARN).build())
      .targetHealthDescriptions.asScala.toList.map(_.targetHealth.state.toString)

  def instanceHealth(elbName: String, client: ClassicELB): List[String] =
    client.describeInstanceHealth(DescribeInstanceHealthRequest.builder().loadBalancerName(elbName).build())
      .instanceStates.asScala.toList.map(_.state)

  def deregister(elbName: List[String], elbTargetARN: List[String], instance: ASGInstance, client: Client) = {
    elbName.foreach(name =>
      client.classic.deregisterInstancesFromLoadBalancer(
        DeregisterInstancesFromLoadBalancerRequest.builder()
          .loadBalancerName(name)
          .instances(ELBInstance.builder().instanceId(instance.instanceId).build())
          .build()
      ))
    elbTargetARN.foreach(arn =>
      client.application.deregisterTargets(
        DeregisterTargetsRequest.builder()
          .targetGroupArn(arn)
          .targets(TargetDescription.builder().id(instance.instanceId).build())
          .build()
      ))
  }
}

object EC2 extends AWS {
  def makeEc2Client(keyRing: KeyRing, region: Region): Ec2Client = {
    Ec2Client.builder()
      .credentialsProvider(provider(keyRing))
      .overrideConfiguration(clientConfiguration)
      .region(region.awsRegion)
      .build()
  }

  def setTag(instances: List[ASGInstance], key: String, value: String, client: Ec2Client) {
    val request = CreateTagsRequest.builder()
      .resources(instances.map(_.instanceId).asJavaCollection)
      .tags(EC2Tag.builder().key(key).value(value).build())
        .build()

    client.createTags(request)
  }

  def hasTag(instance: ASGInstance, key: String, value: String, client: Ec2Client): Boolean = {
    describe(instance, client).tags.asScala.exists { tag =>
      tag.key == key && tag.value == value
    }
  }

  def describe(instance: ASGInstance, client: Ec2Client) = client.describeInstances(
    DescribeInstancesRequest.builder().instanceIds(instance.instanceId).build()
  ).reservations.asScala.flatMap(_.instances.asScala).head

  def apply(instance: ASGInstance, client: Ec2Client) = describe(instance, client)
}

object CloudFormation extends AWS {
  sealed trait ParameterValue
  case class SpecifiedValue(value: String) extends ParameterValue
  case object UseExistingValue extends ParameterValue

  sealed trait Template
  case class TemplateBody(body: String) extends Template
  case class TemplateUrl(url: String) extends Template

  def makeCfnClient(keyRing: KeyRing, region: Region): CloudFormationClient = {
    CloudFormationClient.builder()
      .credentialsProvider(provider(keyRing))
      .overrideConfiguration(clientConfiguration)
      .region(region.awsRegion)
      .build()
  }

  def validateTemplate(template: Template, client: CloudFormationClient) = {
    val request = template match {
      case TemplateBody(body) => ValidateTemplateRequest.builder().templateBody(body)
      case TemplateUrl(url) => ValidateTemplateRequest.builder().templateURL(url)
    }
    client.validateTemplate(request.build())
  }

  def updateStackParams(name: String, parameters: Map[String, ParameterValue], client: CloudFormationClient): UpdateStackResponse =
    client.updateStack(
      UpdateStackRequest.builder()
        .stackName(name)
        .capabilities(Capability.CAPABILITY_NAMED_IAM)
        .usePreviousTemplate(true)
        .parameters(
          parameters.map {
            case (k, SpecifiedValue(v)) => Parameter.builder().parameterKey(k).parameterValue(v).build()
            case (k, UseExistingValue) => Parameter.builder().parameterKey(k).usePreviousValue(true).build()
          }.toSeq: _*
        )
        .build()
    )

  def createChangeSet(reporter: DeployReporter, name: String, tpe: ChangeSetType, stackName: String, maybeTags: Option[Map[String, String]],
                      template: Template, parameters: Iterable[Parameter], client: CloudFormationClient): Unit = {

    val request = CreateChangeSetRequest.builder()
      .changeSetName(name)
      .changeSetType(tpe)
      .stackName(stackName)
      .capabilities(Capability.CAPABILITY_NAMED_IAM)
      .parameters(parameters.toSeq.asJava)

    val tags: Iterable[CfnTag] = maybeTags
      .getOrElse(Map.empty)
      .map  { case (key, value) => CfnTag.builder().key(key).value(value).build() }

    request.tags(tags.toSeq: _*)

    val requestWithTemplate = template match {
      case TemplateBody(body) => request.templateBody(body)
      case TemplateUrl(url) => request.templateURL(url)
    }

    client.createChangeSet(requestWithTemplate.build())
  }

  def describeStack(name: String, client: CloudFormationClient) =
    try {
      client.describeStacks(
        DescribeStacksRequest.builder().stackName(name).build()
      ).stacks.asScala.headOption
    } catch {
      case acfe: CloudFormationException
        if acfe.awsErrorDetails.errorCode == "ValidationError" && acfe.awsErrorDetails.errorMessage.contains("does not exist") => None
    }

  def describeStackEvents(name: String, client: CloudFormationClient) =
    client.describeStackEvents(
      DescribeStackEventsRequest.builder().stackName(name).build()
    )

  def findStackByTags(tags: Map[String, String], reporter: DeployReporter, client: CloudFormationClient): Option[AmazonStack] = {

    def tagsMatch(stack: AmazonStack): Boolean =
      tags.forall { case (key, value) => stack.tags.asScala.exists(t => t.key == key && t.value == value) }

    @tailrec
    def recur(nextToken: Option[String] = None, existingStacks: List[AmazonStack] = Nil): List[AmazonStack] = {
      val request = DescribeStacksRequest.builder().nextToken(nextToken.orNull).build()
      val response = client.describeStacks(request)

      val stacks = response.stacks.asScala.foldLeft(existingStacks) {
        case (agg, stack) if tagsMatch(stack) => stack :: agg
        case (agg, _) => agg
      }

      Option(response.nextToken) match {
        case None => stacks
        case token => recur(token, stacks)
      }
    }

    recur() match {
      case cfnStack :: Nil =>
        reporter.verbose(s"Found stack ${cfnStack.stackName} (${cfnStack.stackId})")
        Some(cfnStack)
      case Nil =>
        None
      case multipleStacks =>
        reporter.fail(s"More than one cloudformation stack match for $tags (matched ${multipleStacks.map(_.stackName).mkString(", ")}). Failing fast since this may be non-deterministic.")
    }
  }
}

object STS extends AWS {
  def makeSTSclient(keyRing: KeyRing, region: Region): StsClient = {
    StsClient.builder()
      .credentialsProvider(provider(keyRing))
      .overrideConfiguration(clientConfiguration)
      .region(region.awsRegion)
      .build()
  }

  def getAccountNumber(stsClient: StsClient): String = {
    val callerIdentityResponse = stsClient.getCallerIdentity(GetCallerIdentityRequest.builder().build())
    callerIdentityResponse.account
  }
}

trait AWS extends Loggable {
  lazy val accessKey: String = Option(System.getenv.get("aws_access_key")).getOrElse{
    sys.error("Cannot authenticate, 'aws_access_key' must be set as a system property")
  }
  lazy val secretAccessKey: String = Option(System.getenv.get("aws_secret_access_key")).getOrElse{
    sys.error("Cannot authenticate, aws_secret_access_key' must be set as a system property")
  }

  lazy val envCredentials: AwsBasicCredentials = AwsBasicCredentials.create(accessKey, secretAccessKey)

  def provider(keyRing: KeyRing): AwsCredentialsProviderChain = AwsCredentialsProviderChain.builder().credentialsProviders(
    new AwsCredentialsProvider {
      override def resolveCredentials(): AwsCredentials = keyRing.apiCredentials.get("aws").map { credentials =>
        AwsBasicCredentials.create(credentials.id, credentials.secret)
      }.get
    },
    new AwsCredentialsProvider {
      override def resolveCredentials(): AwsCredentials = envCredentials
    }
  ).build()

  /* A retry condition that logs errors */
  class LoggingRetryCondition extends RetryCondition {
    private def exceptionInfo(e: Throwable): String = {
      s"${e.getClass.getName} ${e.getMessage} Cause: ${Option(e.getCause).map(e => exceptionInfo(e))}"
    }
    override def shouldRetry(context: RetryPolicyContext): Boolean = {
      val willRetry = shouldRetry(context)
      if (willRetry) {
        logger.warn(s"AWS SDK retry ${context.retriesAttempted}: ${Option(context.originalRequest).map(_.getClass.getName)} threw ${exceptionInfo(context.exception)}")
      } else {
        logger.warn(s"Encountered fatal exception during AWS API call", context.exception)
        Option(context.exception.toBuilder.cause).foreach(t => logger.warn(s"Cause of fatal exception", t))
      }
      willRetry
    }
  }

  val clientConfiguration: ClientOverrideConfiguration =
    ClientOverrideConfiguration.builder()
      .retryPolicy(
        RetryPolicy.builder()
          .retryCondition(new LoggingRetryCondition())
          .backoffStrategy(BackoffStrategy.defaultStrategy())
          .numRetries(20)
          .build()
    ).build()

  val clientConfigurationNoRetry: ClientOverrideConfiguration =
    ClientOverrideConfiguration.builder()
      .retryPolicy(
        RetryPolicy.none()
      ).build()
}
