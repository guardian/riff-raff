package magenta.tasks

import java.nio.ByteBuffer
import cats.implicits._
import magenta.deployment_type.{
  MigrationTagRequirements,
  MustBePresent,
  MustNotBePresent
}
import magenta.{
  ApiRoleCredentials,
  ApiStaticCredentials,
  App,
  DeployReporter,
  DeploymentPackage,
  DeploymentResources,
  KeyRing,
  Loggable,
  Region,
  Stack,
  Stage,
  Strategy,
  StsDeploymentResources,
  withResource
}
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  AwsCredentials,
  AwsCredentialsProvider,
  AwsCredentialsProviderChain,
  ProfileCredentialsProvider,
  StaticCredentialsProvider
}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy
import software.amazon.awssdk.core.retry.conditions.RetryCondition
import software.amazon.awssdk.core.retry.{RetryPolicy, RetryPolicyContext}
import software.amazon.awssdk.services.autoscaling.AutoScalingClient
import software.amazon.awssdk.services.autoscaling.model.{
  Instance => ASGInstance,
  _
}
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.{
  Stack => AmazonStack,
  Tag => CfnTag,
  _
}
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.{
  CreateTagsRequest,
  DescribeInstancesRequest,
  Tag => EC2Tag
}
import software.amazon.awssdk.services.elasticloadbalancing.model.{
  DeregisterInstancesFromLoadBalancerRequest,
  DescribeInstanceHealthRequest,
  Instance => ELBInstance
}
import software.amazon.awssdk.services.elasticloadbalancing.{
  ElasticLoadBalancingClient => ClassicELB
}
import software.amazon.awssdk.services.elasticloadbalancingv2.model.{
  DeregisterTargetsRequest,
  DescribeTargetHealthRequest,
  TargetDescription,
  TargetHealthStateEnum
}
import software.amazon.awssdk.services.elasticloadbalancingv2.{
  ElasticLoadBalancingV2Client => ApplicationELB
}
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.{
  FunctionConfiguration,
  LayerVersionContentInput,
  ListFunctionsRequest,
  ListTagsRequest,
  PublishLayerVersionRequest,
  PublishVersionRequest,
  UpdateFunctionCodeRequest
}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model._
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.{
  GetParameterRequest,
  SsmException
}
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest.Builder

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._
import scala.util.{Random, Try}
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest

object S3 {
  def withS3client[T](
      keyRing: KeyRing,
      region: Region,
      config: ClientOverrideConfiguration = AWS.clientConfiguration,
      resources: DeploymentResources
  )(block: S3Client => T): T =
    withResource(
      S3Client
        .builder()
        .region(region.awsRegion)
        .credentialsProvider(AWS.provider(keyRing, resources))
        .overrideConfiguration(config)
        .build()
    )(block)

  /** Check (and if necessary create) that an S3 bucket exists for the account
    * and region. Note that it is assumed that the clients and region provided
    * all match up - i.e. they are all configured to the same region and use the
    * same credentials.
    * @param prefix
    *   The prefix for the bucket. The resulting bucket will be
    *   <prefix>-<accountNumber>-<region>.
    * @param s3Client
    *   S3 client used to check and create the bucket and set a lifecycle policy
    *   if deleteAfterDays is set
    * @param stsClient
    *   The STS client that is used to retrieve the account number
    * @param region
    *   The region in which the bucket should be created
    * @param deleteAfterDays
    *   If set then if this bucket is created then a lifecycle policy rule is
    *   created to delete objects uploaded to this bucket after the number of
    *   days specified
    *
    * @return
    */
  def accountSpecificBucket(
      prefix: String,
      s3Client: S3Client,
      stsClient: StsClient,
      region: Region,
      reporter: DeployReporter,
      deleteAfterDays: Option[Int] = None
  ): String = {
    val accountNumber = STS.getAccountNumber(stsClient)
    val bucketName = s"$prefix-$accountNumber-${region.name}"
    if (!s3Client.listBuckets.buckets.asScala.exists(_.name == bucketName)) {
      reporter.info(
        s"Creating bucket for this account and region: $bucketName ${region.name}"
      )

      val createBucketRequest =
        CreateBucketRequest.builder().bucket(bucketName).build()
      s3Client.createBucket(createBucketRequest)

      deleteAfterDays.foreach { days =>
        val daysString = s"$days day${if (days == 1) "" else "s"}"
        reporter.info(
          s"Creating lifecycle rule on bucket that deletes objects after $daysString"
        )
        s3Client.putBucketLifecycleConfiguration(
          PutBucketLifecycleConfigurationRequest
            .builder()
            .bucket(bucketName)
            .lifecycleConfiguration(
              BucketLifecycleConfiguration
                .builder()
                .rules(
                  LifecycleRule
                    .builder()
                    .id(s"Rule to delete objects after $daysString")
                    .status(ExpirationStatus.ENABLED)
                    .expiration(
                      LifecycleExpiration.builder().days(days).build()
                    )
                    .build()
                )
                .build()
            )
            .build()
        )
      }
    }
    bucketName
  }

  def getBucketName(
      bucket: Bucket,
      withSsmClient: => (SsmClient => String) => String,
      reporter: => DeployReporter
  ): String = {
    bucket match {
      case BucketByName(name) => name
      case BucketBySsmKey(ssmKey) =>
        try {
          val resolvedBucket = withSsmClient { SSM.getParameter(_, ssmKey) }
          reporter.verbose(
            s"Resolved bucket from SSM key $ssmKey to be $resolvedBucket"
          )
          resolvedBucket
        } catch {
          case e: SsmException =>
            reporter.fail(
              s"Explicit bucket name has not been provided and failed to read bucket from SSM parameter: $ssmKey",
              e
            )
        }
    }
  }

  sealed trait Bucket
  case class BucketByName(name: String) extends Bucket
  case class BucketBySsmKey(ssmKey: String) extends Bucket
}

object Lambda {
  def withLambdaClient[T](
      keyRing: KeyRing,
      region: Region,
      resources: DeploymentResources
  )(block: LambdaClient => T): T =
    withResource(
      LambdaClient
        .builder()
        .credentialsProvider(AWS.provider(keyRing, resources))
        .overrideConfiguration(AWS.clientConfiguration)
        .region(region.awsRegion)
        .build()
    )(block)

  def lambdaUpdateFunctionCodeRequest(
      functionName: String,
      buffer: ByteBuffer
  ): UpdateFunctionCodeRequest =
    UpdateFunctionCodeRequest
      .builder()
      .functionName(functionName)
      .zipFile(SdkBytes.fromByteBuffer(buffer))
      .build()

  def lambdaUpdateFunctionCodeRequest(
      functionName: String,
      s3Bucket: String,
      s3Key: String
  ): UpdateFunctionCodeRequest =
    UpdateFunctionCodeRequest
      .builder()
      .functionName(functionName)
      .s3Bucket(s3Bucket)
      .s3Key(s3Key)
      .build()

  def lambdaPublishLayerVersionRequest(
      layerName: String,
      s3Bucket: String,
      s3Key: String
  ): PublishLayerVersionRequest = {
    val layerContent = LayerVersionContentInput
      .builder()
      .s3Bucket(s3Bucket)
      .s3Key(s3Key)
      .build()

    PublishLayerVersionRequest
      .builder()
      .layerName(layerName)
      .content(layerContent)
      .build()
  }

  def findFunctionByTags(
      tags: Map[String, String],
      reporter: DeployReporter,
      client: LambdaClient
  ): Option[FunctionConfiguration] = {

    def tagsMatch(function: FunctionConfiguration): Boolean = {
      val tagResponse = client.listTags(
        ListTagsRequest.builder().resource(function.functionArn).build()
      )
      val functionTags = tagResponse.tags().asScala.toSet
      tags.forall { functionTags.contains }
    }

    val response = client.listFunctionsPaginator()
    val matchingConfigurations =
      response.functions().asScala.filter(tagsMatch).toList

    matchingConfigurations match {
      case functionConfiguration :: Nil =>
        reporter.verbose(
          s"Found function ${functionConfiguration.functionName} (${functionConfiguration.functionArn})"
        )
        Some(functionConfiguration)
      case Nil =>
        None
      case multiple =>
        reporter.fail(
          s"More than one function matched for $tags (matched ${multiple.map(_.functionName).mkString(", ")}). Failing fast since this may be non-deterministic."
        )
    }
  }
}

object ASG {
  def withAsgClient[T](
      keyRing: KeyRing,
      region: Region,
      resources: DeploymentResources
  )(block: AutoScalingClient => T): T =
    withResource(
      AutoScalingClient
        .builder()
        .credentialsProvider(AWS.provider(keyRing, resources))
        .overrideConfiguration(AWS.clientConfiguration)
        .region(region.awsRegion)
        .build()
    )(block)

  def desiredCapacity(name: String, capacity: Int, client: AutoScalingClient) =
    client.setDesiredCapacity(
      SetDesiredCapacityRequest
        .builder()
        .autoScalingGroupName(name)
        .desiredCapacity(capacity)
        .build()
    )

  def maxCapacity(name: String, capacity: Int, client: AutoScalingClient) =
    client.updateAutoScalingGroup(
      UpdateAutoScalingGroupRequest
        .builder()
        .autoScalingGroupName(name)
        .maxSize(capacity)
        .build()
    )

  /** Check status of all ELBs, or all instance states if there are no ELBs
    */
  def isStabilized(
      asg: AutoScalingGroup,
      elbClient: ELB.Client
  ): Either[String, Unit] = {

    def matchCapacityAndState(
        states: List[String],
        desiredState: String,
        checkDescription: Option[String]
    ): Either[String, Unit] = {
      val descriptionWithPrecedingSpace =
        checkDescription.map(d => s" $d").getOrElse("")
      if (states.size != asg.desiredCapacity)
        Left(
          s"Number of$descriptionWithPrecedingSpace instances (${states.size}) and ASG desired capacity (${asg.desiredCapacity}) don't match"
        )
      else if (!states.forall(_ == desiredState))
        Left(
          s"Only ${states.count(_ == desiredState)} of ${states.size}$descriptionWithPrecedingSpace instances $desiredState"
        )
      else
        Right(())
    }

    def checkClassicELB(name: String): Either[String, Unit] = {
      val elbHealth = ELB.instanceHealth(name, elbClient.classic)
      matchCapacityAndState(elbHealth, "InService", Some("Classic ELB"))
    }

    def checkTargetGroup(arn: String): Either[String, Unit] = {
      val elbHealth = ELB.targetInstancesHealth(arn, elbClient.application)
      matchCapacityAndState(
        elbHealth,
        TargetHealthStateEnum.HEALTHY.toString,
        Some("V2 ELB")
      )
    }

    val classicElbNames = elbNames(asg)
    val targetGroupArns = elbTargetArns(asg)

    if (classicElbNames.nonEmpty || targetGroupArns.nonEmpty) {
      for {
        _ <- classicElbNames.map(checkClassicELB).sequence
        _ <- targetGroupArns.map(checkTargetGroup).sequence
      } yield ()
    } else {
      val instanceStates =
        asg.instances.asScala.toList.map(_.lifecycleStateAsString)
      matchCapacityAndState(
        instanceStates,
        LifecycleState.IN_SERVICE.toString,
        None
      )
    }
  }

  def elbNames(asg: AutoScalingGroup): List[String] =
    asg.loadBalancerNames.asScala.toList

  def elbTargetArns(asg: AutoScalingGroup): List[String] =
    asg.targetGroupARNs.asScala.toList

  def cull(
      asg: AutoScalingGroup,
      instance: ASGInstance,
      asgClient: AutoScalingClient,
      elbClient: ELB.Client
  ): TerminateInstanceInAutoScalingGroupResponse = {
    ELB.deregister(elbNames(asg), elbTargetArns(asg), instance, elbClient)

    asgClient.terminateInstanceInAutoScalingGroup(
      TerminateInstanceInAutoScalingGroupRequest
        .builder()
        .instanceId(instance.instanceId)
        .shouldDecrementDesiredCapacity(true)
        .build()
    )
  }

  def refresh(
      asg: AutoScalingGroup,
      client: AutoScalingClient
  ): AutoScalingGroup =
    client
      .describeAutoScalingGroups(
        DescribeAutoScalingGroupsRequest
          .builder()
          .autoScalingGroupNames(asg.autoScalingGroupName())
          .build()
      )
      .autoScalingGroups
      .asScala
      .head

  def suspendAlarmNotifications(
      name: String,
      client: AutoScalingClient
  ): SuspendProcessesResponse =
    client.suspendProcesses(
      SuspendProcessesRequest
        .builder()
        .autoScalingGroupName(name)
        .scalingProcesses("AlarmNotification")
        .build()
    )

  def resumeAlarmNotifications(
      name: String,
      client: AutoScalingClient
  ): ResumeProcessesResponse =
    client.resumeProcesses(
      ResumeProcessesRequest
        .builder()
        .autoScalingGroupName(name)
        .scalingProcesses("AlarmNotification")
        .build()
    )

  trait TagRequirement
  case class TagMatch(key: String, value: String) extends TagRequirement
  case class TagExists(key: String) extends TagRequirement
  case class TagAbsent(key: String) extends TagRequirement

  def getGroupByName(
      name: String,
      client: AutoScalingClient,
      reporter: DeployReporter
  ): AutoScalingGroup = {
    val request = DescribeAutoScalingGroupsRequest
      .builder()
      .autoScalingGroupNames(name)
      .maxRecords(1)
      .build()
    val autoScalingGroups = client
      .describeAutoScalingGroups(request)
      .autoScalingGroups()
      .asScala
      .toList
    // We've asked for one record and the name must be unique per Region per account
    autoScalingGroups.headOption.getOrElse(
      reporter.fail(
        s"Failed to identify an autoscaling group with name ${name}"
      )
    )
  }

  def groupWithTags(
      tagRequirements: List[TagRequirement],
      client: AutoScalingClient,
      reporter: DeployReporter,
      strategy: Strategy = Strategy.MostlyHarmless
  ): Option[AutoScalingGroup] = {
    case class ASGMatch(app: App, matches: List[AutoScalingGroup])

    def hasExactTagRequirements(
        asg: AutoScalingGroup,
        requirements: List[TagRequirement]
    ): Boolean = {
      val tags = asg.tags.asScala
      requirements.forall {
        case TagMatch(key, value) =>
          tags.exists(t => t.key == key && t.value == value)
        case TagExists(key) => tags.exists(_.key == key)
        case TagAbsent(key) => tags.forall(_.key != key)
      }
    }

    def listAutoScalingGroups(): List[AutoScalingGroup] = {
      val result = client.describeAutoScalingGroupsPaginator()
      result.autoScalingGroups.asScala.toList
    }

    val groups = listAutoScalingGroups()

    val matches = groups.filter(hasExactTagRequirements(_, tagRequirements))
    matches match {
      case Nil if strategy == Strategy.Dangerous =>
        reporter.info(
          s"Unable to locate an autoscaling group AND living dangerously - we'll assume you're creating a new stack"
        )
        None
      case Nil =>
        reporter.fail(
          s"No autoscaling group found with tags $tagRequirements. Creating a new stack? Initially choose the ${Strategy.Dangerous} strategy."
        )
      case List(singleGroup) => Some(singleGroup)
      case groupList =>
        reporter.fail(
          s"More than one autoscaling group match for $tagRequirements (${groupList.map(_.autoScalingGroupARN).mkString(", ")}). Failing fast since this may be non-deterministic."
        )
    }
  }
}

object ELB {

  case class Client(classic: ClassicELB, application: ApplicationELB)
      extends AutoCloseable {
    def close(): Unit = {
      val classicClose = Try(classic.close())
      val applicationClose = Try(application.close())
      classicClose.flatMap(_ => applicationClose).get
    }
  }

  def withClient[T](
      keyRing: KeyRing,
      region: Region,
      resources: DeploymentResources
  )(block: Client => T): T =
    withResource(
      Client(
        classicClient(keyRing, region, resources),
        applicationClient(keyRing, region, resources)
      )
    )(block)

  private def classicClient(
      keyRing: KeyRing,
      region: Region,
      resources: DeploymentResources
  ): ClassicELB =
    ClassicELB
      .builder()
      .credentialsProvider(AWS.provider(keyRing, resources))
      .overrideConfiguration(AWS.clientConfiguration)
      .region(region.awsRegion)
      .build()

  private def applicationClient(
      keyRing: KeyRing,
      region: Region,
      resources: DeploymentResources
  ): ApplicationELB =
    ApplicationELB
      .builder()
      .credentialsProvider(AWS.provider(keyRing, resources))
      .overrideConfiguration(AWS.clientConfiguration)
      .region(region.awsRegion)
      .build()

  def targetInstancesHealth(
      targetARN: String,
      client: ApplicationELB
  ): List[String] =
    client
      .describeTargetHealth(
        DescribeTargetHealthRequest.builder().targetGroupArn(targetARN).build()
      )
      .targetHealthDescriptions
      .asScala
      .toList
      .map(_.targetHealth.state.toString)

  def instanceHealth(elbName: String, client: ClassicELB): List[String] =
    client
      .describeInstanceHealth(
        DescribeInstanceHealthRequest
          .builder()
          .loadBalancerName(elbName)
          .build()
      )
      .instanceStates
      .asScala
      .toList
      .map(_.state)

  def deregister(
      elbName: List[String],
      elbTargetARN: List[String],
      instance: ASGInstance,
      client: Client
  ) = {
    elbName.foreach(name =>
      client.classic.deregisterInstancesFromLoadBalancer(
        DeregisterInstancesFromLoadBalancerRequest
          .builder()
          .loadBalancerName(name)
          .instances(
            ELBInstance.builder().instanceId(instance.instanceId).build()
          )
          .build()
      )
    )
    elbTargetARN.foreach(arn =>
      client.application.deregisterTargets(
        DeregisterTargetsRequest
          .builder()
          .targetGroupArn(arn)
          .targets(TargetDescription.builder().id(instance.instanceId).build())
          .build()
      )
    )
  }
}

object EC2 {
  def withEc2Client[T](
      keyRing: KeyRing,
      region: Region,
      resources: DeploymentResources
  )(block: Ec2Client => T) = {
    withResource(
      Ec2Client
        .builder()
        .credentialsProvider(AWS.provider(keyRing, resources))
        .overrideConfiguration(AWS.clientConfiguration)
        .region(region.awsRegion)
        .build()
    )(block)
  }

  def setTag(
      instances: List[ASGInstance],
      key: String,
      value: String,
      client: Ec2Client
  ): Unit = {
    val request = CreateTagsRequest
      .builder()
      .resources(instances.map(_.instanceId).asJavaCollection)
      .tags(EC2Tag.builder().key(key).value(value).build())
      .build()

    client.createTags(request)
  }

  def hasTag(
      instance: ASGInstance,
      key: String,
      value: String,
      client: Ec2Client
  ): Boolean = {
    describe(instance, client).tags.asScala.exists { tag =>
      tag.key == key && tag.value == value
    }
  }

  def describe(instance: ASGInstance, client: Ec2Client) = client
    .describeInstances(
      DescribeInstancesRequest
        .builder()
        .instanceIds(instance.instanceId)
        .build()
    )
    .reservations
    .asScala
    .flatMap(_.instances.asScala)
    .head

  def apply(instance: ASGInstance, client: Ec2Client) =
    describe(instance, client)
}

object CloudFormation {
  sealed trait ParameterValue
  case class SpecifiedValue(value: String) extends ParameterValue
  case object UseExistingValue extends ParameterValue

  sealed trait Template
  case class TemplateBody(body: String) extends Template
  case class TemplateUrl(url: String) extends Template

  def getExecutionRole(keyRing: KeyRing): Option[String] = {
    keyRing.apiCredentials
      .get("aws-cfn-role")
      .collect { case ApiRoleCredentials(_, role, _) => role }
  }

  def withCfnClient[T](
      keyRing: KeyRing,
      region: Region,
      resources: DeploymentResources
  )(block: CloudFormationClient => T): T = {
    withResource(
      CloudFormationClient
        .builder()
        .credentialsProvider(AWS.provider(keyRing, resources))
        .overrideConfiguration(AWS.clientConfiguration)
        .region(region.awsRegion)
        .build()
    )(block)
  }

  def validateTemplate(template: Template, client: CloudFormationClient) = {
    val request = template match {
      case TemplateBody(body) =>
        ValidateTemplateRequest.builder().templateBody(body)
      case TemplateUrl(url) =>
        ValidateTemplateRequest.builder().templateURL(url)
    }
    client.validateTemplate(request.build())
  }

  def updateStackParams(
      name: String,
      parameters: Map[String, ParameterValue],
      maybeRole: Option[String],
      client: CloudFormationClient
  ): UpdateStackResponse =
    client.updateStack(
      UpdateStackRequest
        .builder()
        .stackName(name)
        .capabilities(Capability.CAPABILITY_NAMED_IAM)
        .usePreviousTemplate(true)
        .parameters(
          parameters.map {
            case (k, SpecifiedValue(v)) =>
              Parameter.builder().parameterKey(k).parameterValue(v).build()
            case (k, UseExistingValue) =>
              Parameter.builder().parameterKey(k).usePreviousValue(true).build()
          }.toSeq: _*
        )
        .roleARN(maybeRole.orNull)
        .build()
    )

  def createChangeSet(
      reporter: DeployReporter,
      name: String,
      tpe: ChangeSetType,
      stackName: String,
      maybeTags: Option[Map[String, String]],
      template: Template,
      parameters: List[Parameter],
      maybeRole: Option[String],
      client: CloudFormationClient
  ): Unit = {

    val request = CreateChangeSetRequest
      .builder()
      .changeSetName(name)
      .changeSetType(tpe)
      .stackName(stackName)
      .capabilities(Capability.CAPABILITY_NAMED_IAM)
      .parameters(parameters.asJava)
      .roleARN(maybeRole.orNull)

    val tags: Iterable[CfnTag] = maybeTags
      .getOrElse(Map.empty)
      .map { case (key, value) =>
        CfnTag.builder().key(key).value(value).build()
      }

    request.tags(tags.toSeq: _*)

    val requestWithTemplate = template match {
      case TemplateBody(body) => request.templateBody(body)
      case TemplateUrl(url)   => request.templateURL(url)
    }

    client.createChangeSet(requestWithTemplate.build())
  }

  def describeChangeSetByName(
      stackName: String,
      changeSetName: String,
      client: CloudFormationClient
  ): DescribeChangeSetResponse =
    client.describeChangeSet(
      DescribeChangeSetRequest
        .builder()
        .stackName(stackName)
        .changeSetName(changeSetName)
        .build()
    )

  def describeChangeSetByArn(
      changeSetArn: String,
      client: CloudFormationClient
  ): DescribeChangeSetResponse =
    client.describeChangeSet(
      DescribeChangeSetRequest
        .builder()
        .changeSetName(changeSetArn)
        .build()
    )

  def executeChangeSet(
      stackName: String,
      changeSetName: String,
      client: CloudFormationClient
  ): ExecuteChangeSetResponse =
    client.executeChangeSet(
      ExecuteChangeSetRequest
        .builder()
        .stackName(stackName)
        .changeSetName(changeSetName)
        .build()
    )

  def deleteChangeSet(
      stackName: String,
      changeSetName: String,
      client: CloudFormationClient
  ): DeleteChangeSetResponse =
    client.deleteChangeSet(
      DeleteChangeSetRequest
        .builder()
        .stackName(stackName)
        .changeSetName(changeSetName)
        .build()
    )

  def describeStack(name: String, client: CloudFormationClient) =
    try {
      client
        .describeStacks(
          DescribeStacksRequest.builder().stackName(name).build()
        )
        .stacks
        .asScala
        .headOption
    } catch {
      case acfe: CloudFormationException
          if acfe.awsErrorDetails.errorCode == "ValidationError" && acfe.awsErrorDetails.errorMessage
            .contains("does not exist") =>
        None
    }

  def describeStackEvents(name: String, client: CloudFormationClient) =
    client.describeStackEvents(
      DescribeStackEventsRequest.builder().stackName(name).build()
    )

  def findStackByTags(
      tags: Map[String, String],
      reporter: DeployReporter,
      client: CloudFormationClient
  ): Option[AmazonStack] = {

    def tagsMatch(stack: AmazonStack): Boolean =
      tags.forall { case (key, value) =>
        stack.tags.asScala.exists(t => t.key == key && t.value == value)
      }

    @tailrec
    def recur(
        nextToken: Option[String] = None,
        existingStacks: List[AmazonStack] = Nil
    ): List[AmazonStack] = {
      val request =
        DescribeStacksRequest.builder().nextToken(nextToken.orNull).build()
      val response = client.describeStacks(request)

      val stacks = response.stacks.asScala.foldLeft(existingStacks) {
        case (agg, stack) if tagsMatch(stack) => stack :: agg
        case (agg, _)                         => agg
      }

      Option(response.nextToken) match {
        case None  => stacks
        case token => recur(token, stacks)
      }
    }

    recur() match {
      case cfnStack :: Nil =>
        reporter.verbose(
          s"Found stack ${cfnStack.stackName} (${cfnStack.stackId})"
        )
        Some(cfnStack)
      case Nil =>
        None
      case multipleStacks =>
        reporter.fail(
          s"More than one cloudformation stack match for $tags (matched ${multipleStacks.map(_.stackName).mkString(", ")}). Failing fast since this may be non-deterministic."
        )
    }
  }
}

object STS {
  def withSTSclient[T](
      keyRing: KeyRing,
      region: Region,
      resources: StsDeploymentResources
  )(block: StsClient => T): T = {
    withResource(
      StsClient
        .builder()
        .credentialsProvider(AWS.provider(keyRing, resources))
        .overrideConfiguration(AWS.clientConfiguration)
        .region(region.awsRegion)
        .build()
    )(block)
  }

  def withSTSclient[T](
      keyRing: KeyRing,
      region: Region,
      resources: DeploymentResources
  )(block: StsClient => T): T = {
    withSTSclient(
      keyRing,
      region,
      StsDeploymentResources.fromDeploymentResources(resources)
    )(block)
  }

  def getAccountNumber(stsClient: StsClient): String = {
    val callerIdentityResponse = stsClient.getCallerIdentity()
    callerIdentityResponse.account
  }
}

object SSM {
  def withSsmClient[T](
      keyRing: KeyRing,
      region: Region,
      resources: DeploymentResources
  )(block: SsmClient => T): T = {
    withResource(
      SsmClient
        .builder()
        .credentialsProvider(AWS.provider(keyRing, resources))
        .overrideConfiguration(AWS.clientConfiguration)
        .region(region.awsRegion)
        .build()
    )(block)
  }

  def getParameter(ssmClient: SsmClient, key: String): String =
    ssmClient
      .getParameter(GetParameterRequest.builder.name(key).build)
      .parameter
      .value
}

object AWS extends Loggable {
  lazy val accessKey: String =
    Option(System.getenv.get("aws_access_key")).getOrElse {
      sys.error(
        "Cannot authenticate, 'aws_access_key' must be set as a system property"
      )
    }
  lazy val secretAccessKey: String =
    Option(System.getenv.get("aws_secret_access_key")).getOrElse {
      sys.error(
        "Cannot authenticate, aws_secret_access_key' must be set as a system property"
      )
    }

  def getRoleCredentialsProvider(
      role: String,
      resources: StsDeploymentResources
  ): StsAssumeRoleCredentialsProvider = {
    logger.info(s"building sts client for role $role")
    val accountNumber = STS.getAccountNumber(resources.stsClient)
    val req: AssumeRoleRequest = AssumeRoleRequest.builder
      .roleSessionName(s"riffraff-session-${resources.deployId}")
      .externalId(accountNumber)
      .roleArn(role)
      .build()
    StsAssumeRoleCredentialsProvider
      .builder()
      .stsClient(resources.stsClient)
      .refreshRequest(req)
      .build();
  }

  def provider(
      keyRing: KeyRing,
      resources: DeploymentResources
  ): AwsCredentialsProviderChain =
    provider(keyRing, StsDeploymentResources.fromDeploymentResources(resources))

  def provider(
      keyRing: KeyRing,
      resources: StsDeploymentResources
  ): AwsCredentialsProviderChain = {
    val roleProvider: Option[AwsCredentialsProvider] =
      keyRing.apiCredentials.get("aws-role").collect {
        case credentials: ApiRoleCredentials =>
          getRoleCredentialsProvider(credentials.id, resources)
      }
    val staticProvider: Option[AwsCredentialsProvider] =
      keyRing.apiCredentials.get("aws").collect {
        case credentials: ApiStaticCredentials =>
          StaticCredentialsProvider.create(
            AwsBasicCredentials.create(credentials.id, credentials.secret)
          )
      }

    (roleProvider, staticProvider) match {
      case (Some(rp), _) =>
        logger.info(s"using role provider for deploy id: ${resources.deployId}")
        AwsCredentialsProviderChain.builder().credentialsProviders(rp).build()
      case (_, Some(sp)) =>
        logger.info(
          s"using static credentials provider for deploy id: ${resources.deployId}"
        )
        AwsCredentialsProviderChain.builder().credentialsProviders(sp).build()
      case _ =>
        throw new IllegalArgumentException(
          s"Could not find credentials provider"
        )
    }
  }

  private lazy val numberOfRetries = 20

  /* A retry condition that logs errors */
  class LoggingRetryCondition(retryCondition: RetryCondition)
      extends RetryCondition {
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
        logger.warn(s"AWS SDK retry ${context.retriesAttempted}: ${Option(context.originalRequest)
            .map(_.getClass.getName)} threw ${exceptionInfo(context.exception)}")
      } else {
        logger.warn(
          s"Encountered fatal exception during AWS API call",
          context.exception
        )
        Option(context.exception.toBuilder.cause).foreach(t =>
          logger.warn(s"Cause of fatal exception", t)
        )
      }
      willRetry
    }
  }

  val clientConfiguration: ClientOverrideConfiguration = {
    val retryPolicy = RetryPolicy.defaultRetryPolicy
    ClientOverrideConfiguration
      .builder()
      .retryPolicy(
        RetryPolicy
          .builder()
          .backoffStrategy(retryPolicy.backoffStrategy())
          .throttlingBackoffStrategy(retryPolicy.throttlingBackoffStrategy())
          .retryCondition(
            new LoggingRetryCondition(retryPolicy.retryCondition())
          )
          .numRetries(numberOfRetries)
          .build()
      )
      .build()
  }

  val clientConfigurationNoRetry: ClientOverrideConfiguration =
    ClientOverrideConfiguration
      .builder()
      .retryPolicy(
        RetryPolicy.none()
      )
      .build()
}
