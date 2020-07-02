package magenta.tasks

import java.nio.ByteBuffer
import java.util

import cats.implicits._
import com.gu.management.Loggable
import magenta.{App, DeployReporter, DeploymentPackage, DeploymentResources, KeyRing, Region, Stack, Stage, StsDeploymentResources, withResource}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsCredentials, AwsCredentialsProvider, AwsCredentialsProviderChain, ProfileCredentialsProvider, StaticCredentialsProvider}
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
import software.amazon.awssdk.services.lambda.model.{FunctionConfiguration, ListFunctionsRequest, ListTagsRequest, UpdateFunctionCodeRequest}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model._
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest.Builder

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.{Random, Try}
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest

object S3 {
  def withS3client[T](keyRing: KeyRing, region: Region, config: ClientOverrideConfiguration = AWS.clientConfiguration, resources: DeploymentResources)(block: S3Client => T): T =
    withResource(S3Client.builder()
      .region(region.awsRegion)
      .credentialsProvider(AWS.provider(keyRing, resources))
      .overrideConfiguration(config)
      .build())(block)

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

  def getBucketName(bucket: Bucket, withSsmClient: => (SsmClient => String) => String, reporter: => DeployReporter): String = {
    bucket match {
      case BucketByName(name) => name
      case BucketBySsmKey(ssmKey) =>
        val resolvedBucket = withSsmClient { SSM.getParameter(_, ssmKey) }
        reporter.verbose(s"Resolved bucket from SSM key $ssmKey to be $resolvedBucket")
        resolvedBucket
    }
  }

  sealed trait Bucket
  case class BucketByName(name: String) extends Bucket
  case class BucketBySsmKey(ssmKey: String) extends Bucket
}

object Lambda {
  def withLambdaClient[T](keyRing: KeyRing, region: Region, resources: DeploymentResources)(block: LambdaClient => T): T =
    withResource(LambdaClient.builder()
    .credentialsProvider(AWS.provider(keyRing, resources))
    .overrideConfiguration(AWS.clientConfiguration)
    .region(region.awsRegion)
    .build())(block)

  def lambdaUpdateFunctionCodeRequest(functionName: String, buffer: ByteBuffer): UpdateFunctionCodeRequest =
    UpdateFunctionCodeRequest.builder().functionName(functionName).zipFile(SdkBytes.fromByteBuffer(buffer)).build()

  def lambdaUpdateFunctionCodeRequest(functionName: String, s3Bucket: String, s3Key: String): UpdateFunctionCodeRequest =
    UpdateFunctionCodeRequest.builder()
      .functionName(functionName)
      .s3Bucket(s3Bucket)
      .s3Key(s3Key)
      .build()

  def findFunctionByTags(tags: Map[String, String], reporter: DeployReporter, client: LambdaClient): Option[FunctionConfiguration] = {

    def tagsMatch(function: FunctionConfiguration): Boolean = {
      val tagResponse = client.listTags(ListTagsRequest.builder().resource(function.functionArn).build())
      val functionTags = tagResponse.tags().asScala.toSet
      tags.forall { functionTags.contains }
    }

    val response = client.listFunctionsPaginator()
    val matchingConfigurations = response.functions().asScala.filter(tagsMatch).toList

    matchingConfigurations match {
      case functionConfiguration :: Nil =>
        reporter.verbose(s"Found function ${functionConfiguration.functionName} (${functionConfiguration.functionArn})")
        Some(functionConfiguration)
      case Nil =>
        None
      case multiple =>
        reporter.fail(s"More than one function matched for $tags (matched ${multiple.map(_.functionName).mkString(", ")}). Failing fast since this may be non-deterministic.")
    }
  }
}

object ASG {
  def withAsgClient[T](keyRing: KeyRing, region: Region, resources: DeploymentResources)(block: AutoScalingClient => T): T =
    withResource(AutoScalingClient.builder()
      .credentialsProvider(AWS.provider(keyRing, resources))
      .overrideConfiguration(AWS.clientConfiguration)
      .region(region.awsRegion)
      .build())(block)

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

object ELB {

  case class Client(classic: ClassicELB, application: ApplicationELB) extends AutoCloseable {
    def close(): Unit = {
      val classicClose = Try(classic.close())
      val applicationClose = Try(application.close())
      classicClose.flatMap(_ => applicationClose).get
    }
  }

  def withClient[T](keyRing: KeyRing, region: Region, resources: DeploymentResources)(block: Client => T): T =
    withResource(Client(classicClient(keyRing, region, resources), applicationClient(keyRing, region, resources)))(block)

  private def classicClient(keyRing: KeyRing, region: Region, resources: DeploymentResources): ClassicELB =
    ClassicELB.builder()
      .credentialsProvider(AWS.provider(keyRing, resources))
      .overrideConfiguration(AWS.clientConfiguration)
      .region(region.awsRegion)
      .build()

  private def applicationClient(keyRing: KeyRing, region: Region, resources: DeploymentResources): ApplicationELB =
    ApplicationELB.builder()
      .credentialsProvider(AWS.provider(keyRing, resources))
      .overrideConfiguration(AWS.clientConfiguration)
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

object EC2 {
  def withEc2Client[T](keyRing: KeyRing, region: Region, resources: DeploymentResources)(block: Ec2Client => T) = {
    withResource(Ec2Client.builder()
      .credentialsProvider(AWS.provider(keyRing, resources))
      .overrideConfiguration(AWS.clientConfiguration)
      .region(region.awsRegion)
      .build())(block)
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

object CloudFormation {
  sealed trait ParameterValue
  case class SpecifiedValue(value: String) extends ParameterValue
  case object UseExistingValue extends ParameterValue

  sealed trait Template
  case class TemplateBody(body: String) extends Template
  case class TemplateUrl(url: String) extends Template

  def withCfnClient[T](keyRing: KeyRing, region: Region, resources: DeploymentResources)(block: CloudFormationClient => T): T = {
    withResource(CloudFormationClient.builder()
      .credentialsProvider(AWS.provider(keyRing, resources))
      .overrideConfiguration(AWS.clientConfiguration)
      .region(region.awsRegion)
      .build())(block)
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

  def createChangeSetRequest(name: String,
                             tpe: ChangeSetType,
                             stackName: String,
                             maybeTags: Option[Map[String, String]],
                             template: Template,
                             parameters: List[Parameter]
                            ): CreateChangeSetRequest = {
    val maybeCfnTags: Option[util.List[CfnTag]] = maybeTags.map { tags =>
      tags.map { case (key, value) => CfnTag.builder().key(key).value(value).build() }.toSeq.asJava
    }

    val request = CreateChangeSetRequest.builder()
      .changeSetName(name)
      .changeSetType(tpe)
      .stackName(stackName)
      .capabilities(Capability.CAPABILITY_NAMED_IAM)
      .parameters(parameters.asJava)
      .tags(maybeCfnTags.orNull)

    val requestWithTemplate = template match {
      case TemplateBody(body) => request.templateBody(body)
      case TemplateUrl(url) => request.templateURL(url)
    }

    requestWithTemplate.build()
  }

  def createChangeSet(name: String, tpe: ChangeSetType, stackName: String, maybeTags: Option[Map[String, String]],
                      template: Template, parameters: List[Parameter], client: CloudFormationClient): Unit = {

    val request = createChangeSetRequest(name, tpe, stackName, maybeTags, template, parameters)

    client.createChangeSet(request)
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

object STS {
  def withSTSclient[T](keyRing: KeyRing, region: Region, resources: StsDeploymentResources)(block: StsClient => T): T = {
    withResource(StsClient.builder()
      .credentialsProvider(AWS.provider(keyRing, resources))
      .overrideConfiguration(AWS.clientConfiguration)
      .region(region.awsRegion)
      .build())(block)
  }

  def withSTSclient[T](keyRing: KeyRing, region: Region, resources: DeploymentResources)(block: StsClient => T): T = {
    withSTSclient(keyRing, region, StsDeploymentResources.fromDeploymentResources(resources))(block)
  }

  def getAccountNumber(stsClient: StsClient): String = {
    val callerIdentityResponse = stsClient.getCallerIdentity()
    callerIdentityResponse.account
  }
}

object SSM {
  def withSsmClient[T](keyRing: KeyRing, region: Region, resources: DeploymentResources)(block: SsmClient => T): T = {
    withResource(SsmClient.builder()
      .credentialsProvider(AWS.provider(keyRing, resources))
      .overrideConfiguration(AWS.clientConfiguration)
      .region(region.awsRegion)
      .build())(block)
  }

  def getParameter(ssmClient: SsmClient, key: String): String = {
    val result = ssmClient.getParameter(GetParameterRequest.builder.name(key).build)
    result.parameter.value
  }
}

object AWS extends Loggable {
  lazy val accessKey: String = Option(System.getenv.get("aws_access_key")).getOrElse{
    sys.error("Cannot authenticate, 'aws_access_key' must be set as a system property")
  }
  lazy val secretAccessKey: String = Option(System.getenv.get("aws_secret_access_key")).getOrElse{
    sys.error("Cannot authenticate, aws_secret_access_key' must be set as a system property")
  }

  def getRoleCredentialsProvider(role: String, resources: StsDeploymentResources): StsAssumeRoleCredentialsProvider = {
      logger.info(s"building sts client for role $role")
      val req: AssumeRoleRequest = AssumeRoleRequest.builder
        .roleSessionName(s"riffraff-session-${resources.deployId}")
        .roleArn(role)
        .build()
      StsAssumeRoleCredentialsProvider.builder()
        .stsClient(resources.stsClient)
        .refreshRequest(req)
        .build();
  }

  def provider(keyRing: KeyRing, resources: DeploymentResources): AwsCredentialsProviderChain = provider(keyRing, StsDeploymentResources.fromDeploymentResources(resources))

  def provider(keyRing: KeyRing, resources: StsDeploymentResources): AwsCredentialsProviderChain = {
    val roleProvider: Option[AwsCredentialsProvider] = keyRing.apiCredentials.get("aws-role").map { credentials =>
      getRoleCredentialsProvider(credentials.id, resources)
    }
    val staticProvider: Option[AwsCredentialsProvider] = keyRing.apiCredentials.get("aws").map { credentials =>
        StaticCredentialsProvider.create(AwsBasicCredentials.create(credentials.id, credentials.secret))
    }

    (roleProvider, staticProvider) match {
      case (Some(rp), _) =>
        logger.info(s"using role provider for deploy id: ${resources.deployId}")
        AwsCredentialsProviderChain.builder().credentialsProviders(rp).build()
      case (_, Some(sp)) =>
        logger.info(s"using static credentials provider for deploy id: ${resources.deployId}")
        AwsCredentialsProviderChain.builder().credentialsProviders(sp).build()
      case _ => throw new IllegalArgumentException(s"Could not find credentials provider")
    }

  }

  private lazy val numberOfRetries = 20

  /* A retry condition that logs errors */
  class LoggingRetryCondition(retryCondition: RetryCondition) extends RetryCondition {
    private def exceptionInfo(e: Throwable): String = {
      s"${e.getClass.getName} ${e.getMessage} Cause: ${Option(e.getCause).map(e => exceptionInfo(e))}"
    }
    private def localRules(context: RetryPolicyContext): Boolean = {
      context.exception().getMessage.contains("Rate exceeded")
    }
    override def shouldRetry(context: RetryPolicyContext): Boolean = {
      val underlyingRetry = retryCondition.shouldRetry(context)
      val willRetry = underlyingRetry || localRules(context)
      if (willRetry) {
        logger.warn(s"AWS SDK retry ${context.retriesAttempted}: ${Option(context.originalRequest).map(_.getClass.getName)} threw ${exceptionInfo(context.exception)}")
      } else {
        logger.warn(s"Encountered fatal exception during AWS API call", context.exception)
        Option(context.exception.toBuilder.cause).foreach(t => logger.warn(s"Cause of fatal exception", t))
      }
      willRetry
    }
  }

  val clientConfiguration: ClientOverrideConfiguration = {
    val retryPolicy = RetryPolicy.defaultRetryPolicy
    ClientOverrideConfiguration.builder()
      .retryPolicy(
        RetryPolicy.builder()
          .backoffStrategy(retryPolicy.backoffStrategy())
          .throttlingBackoffStrategy(retryPolicy.throttlingBackoffStrategy())
          .retryCondition(new LoggingRetryCondition(retryPolicy.retryCondition()))
          .numRetries(numberOfRetries)
          .build()
    ).build()
  }

  val clientConfigurationNoRetry: ClientOverrideConfiguration =
    ClientOverrideConfiguration.builder()
      .retryPolicy(
        RetryPolicy.none()
      ).build()
}
