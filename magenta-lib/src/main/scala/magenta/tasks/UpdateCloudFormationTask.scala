package magenta.tasks

import magenta.deployment_type.CloudFormationDeploymentTypeParameters._
import magenta.tasks.CloudFormation._
import magenta.tasks.UpdateCloudFormationTask.{
  CloudFormationStackLookupStrategy,
  LookupByName,
  LookupByTags
}
import magenta.{
  DeployReporter,
  DeployTarget,
  DeploymentPackage,
  DeploymentResources,
  KeyRing,
  Loggable,
  Region,
  Stack,
  Stage
}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.{
  ChangeSetType,
  CloudFormationException,
  Parameter,
  StackEvent
}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.sts.StsClient

import java.time.{Duration, Instant}
import java.time.Duration.{between, ofMinutes, ofSeconds}
import scala.annotation.tailrec
import scala.jdk.CollectionConverters._
import scala.math.Ordering.Implicits._

/** A simple trait to aid with attempting an update multiple times in the case
  * that an update is already running.
  */
trait RetryCloudFormationUpdate {
  def duration: Duration = ofMinutes(15)
  def calculateSleepTime(currentAttempt: Int): Duration = ofSeconds(30)

  def updateWithRetry[T](reporter: DeployReporter, stopFlag: => Boolean)(
      theUpdate: => T
  ): Option[T] = {
    val expiry = Instant.now().plus(duration)

    def updateAttempt(currentAttempt: Int): Option[T] = {
      try {
        Some(theUpdate)
      } catch {
        // this isn't great, but it seems to be the best that we can realistically do
        case e: CloudFormationException
            if e.awsErrorDetails.errorMessage.matches(
              "^Stack:.* is in [A-Z_]* state and can not be updated."
            ) =>
          if (stopFlag) {
            reporter.info(
              "Abandoning remaining checks as stop flag has been set"
            )
            None
          } else {
            val remainingTime = between(Instant.now(), expiry)
            if (remainingTime.isNegative)
              reporter.fail(
                s"Update is still running after $duration milliseconds (tried $currentAttempt times) - aborting"
              )
            else {
              val sleepyTime = calculateSleepTime(currentAttempt)
              reporter.verbose(
                f"Another update is running against this cloudformation stack, waiting for it to finish (Will wait for a further ${remainingTime.toSeconds} seconds, retrying again after ${sleepyTime.toSeconds}s)"
              )
              Thread.sleep(sleepyTime.toMillis)
              updateAttempt(currentAttempt + 1)
            }
          }
        case e: CloudFormationException =>
          // this might be useful for debugging in the future if a message is seen that we don't catch
          reporter.verbose(e.awsErrorDetails.errorMessage)
          throw e
      }
    }
    updateAttempt(1)
  }
}

class CloudFormationStackMetadata(
    val strategy: CloudFormationStackLookupStrategy,
    val changeSetName: String,
    createStackIfAbsent: Boolean
) {
  import CloudFormationStackMetadata._
  import CloudFormationParameters.ExistingParameter

  def lookup(
      reporter: DeployReporter,
      cfnClient: CloudFormationClient
  ): (String, ChangeSetType, List[ExistingParameter]) = {
    val existingStack = strategy match {
      case LookupByName(name) => CloudFormation.describeStack(name, cfnClient)
      case LookupByTags(tags) =>
        CloudFormation.findStackByTags(tags, reporter, cfnClient)
    }

    val stackName =
      existingStack.map(_.stackName).getOrElse(getNewStackName(strategy))
    val stackParameters = existingStack.toList
      .flatMap(_.parameters.asScala)
      .map(p =>
        ExistingParameter(
          p.parameterKey,
          p.parameterValue,
          Option(p.resolvedValue)
        )
      )
    val changeSetType = getChangeSetType(
      stackName,
      existingStack.nonEmpty,
      createStackIfAbsent,
      reporter
    )

    (stackName, changeSetType, stackParameters)
  }
}

object CloudFormationStackMetadata {
  def getChangeSetType(
      stackName: String,
      stackExists: Boolean,
      createStackIfAbsent: Boolean,
      reporter: DeployReporter
  ): ChangeSetType = {
    if (!stackExists && !createStackIfAbsent) {
      reporter.fail(
        s"Stack $stackName doesn't exist and createStackIfAbsent is false"
      )
    } else if (!stackExists) {
      ChangeSetType.CREATE
    } else {
      ChangeSetType.UPDATE
    }
  }

  def getNewStackName(strategy: CloudFormationStackLookupStrategy): String =
    strategy match {
      case LookupByName(name) => name
      case LookupByTags(tags) =>
        val intrinsicKeyOrder = List("Stack", "Stage", "App")
        val orderedTags = tags.toList.sortBy { case (key, value) =>
          // order by the intrinsic ordering and then alphabetically for keys we don't know
          val order = intrinsicKeyOrder.indexOf(key)
          val intrinsicOrdering = if (order == -1) Int.MaxValue else order
          (intrinsicOrdering, key)
        }
        orderedTags.map { case (key, value) => value }.mkString("-")
    }
}

case class CloudFormationParameters(
    target: DeployTarget,
    stackTags: Option[Map[String, String]],
    userParameters: Map[String, String],
    amiParameterMap: Map[CfnParam, TagCriteria],
    latestImage: CfnParam => String => String => Map[String, String] => Option[
      String
    ]
)

object CloudFormationParameters {
  case class TemplateParameter(key: String, default: Boolean)
  case class ExistingParameter(
      key: String,
      value: String,
      resolved: Option[String]
  )
  case class InputParameter(
      key: String,
      value: Option[String],
      usePreviousValue: Boolean
  )
  object InputParameter {
    def apply(key: String, value: String): InputParameter =
      InputParameter(key, Some(value), usePreviousValue = false)
    def usePreviousValue(key: String): InputParameter =
      InputParameter(key, None, usePreviousValue = true)
    def toAWS(p: InputParameter): Parameter = Parameter
      .builder()
      .parameterKey(p.key)
      .parameterValue(p.value.orNull)
      .usePreviousValue(p.usePreviousValue)
      .build()
  }

  def convertInputParametersToAws(
      params: List[InputParameter]
  ): List[Parameter] = params.map(InputParameter.toAWS)

  def resolve(
      reporter: DeployReporter,
      cfnParameters: CloudFormationParameters,
      accountNumber: String,
      templateParameters: List[TemplateParameter],
      existingParameters: List[ExistingParameter]
  ): Either[String, List[InputParameter]] = {

    val resolvedAmiParameters: Map[String, String] =
      cfnParameters.amiParameterMap.flatMap { case (name, tags) =>
        val ami = cfnParameters
          .latestImage(name)(accountNumber)(cfnParameters.target.region.name)(
            tags
          )
        if (ami.isEmpty) {
          val tagsStr = tags.map { case (k, v) => s"$k: $v" }.mkString(", ")
          reporter.fail(
            s"Failed to resolve AMI for parameter $name in account $accountNumber with tags $tagsStr"
          )
        }
        ami.map(name -> _)
      }

    val deploymentParameters = Map(
      "Stage" -> cfnParameters.target.parameters.stage.name,
      "Stack" -> cfnParameters.target.stack.name,
      "BuildId" -> cfnParameters.target.parameters.build.id
    )

    val combined = combineParameters(
      deployParameters = deploymentParameters,
      existingParameters = existingParameters,
      templateParameters = templateParameters,
      specifiedParameters =
        cfnParameters.userParameters ++ resolvedAmiParameters
    )
    combined.map(convertParameters)
  }

  def convertParameters(
      parameters: Map[String, ParameterValue]
  ): List[InputParameter] = {
    parameters.toList map {
      case (k, SpecifiedValue(v)) => InputParameter(k, v)
      case (k, UseExistingValue)  => InputParameter.usePreviousValue(k)
    }
  }

  /** @param deployParameters
    *   Optional parameter values that can be filled in if the template has
    *   matching parameters
    * @param existingParameters
    *   Parameters on the existing stack (if any)
    * @param templateParameters
    *   Parameters in the template that will be applied
    * @param specifiedParameters
    *   These are parameters specified by the user (either explicitly or via an
    *   AMI lookup) - they MUST be included in the list
    * @return
    *   Set of parameters as they should be provided to the cloudformation
    *   template change set
    */
  def combineParameters(
      deployParameters: Map[String, String],
      existingParameters: List[ExistingParameter],
      templateParameters: List[TemplateParameter],
      specifiedParameters: Map[String, String]
  ): Either[String, Map[String, ParameterValue]] = {

    // Start with the complete list of keys that must be found or have default values
    val allRequiredParamNames = templateParameters.map(_.key).toSet
    // use existing value for all template values in the existing parameter list
    val existingParametersMap: Map[String, ParameterValue] =
      existingParameters.collect {
        case ExistingParameter(key, _, _)
            if allRequiredParamNames.contains(key) =>
          key -> UseExistingValue
      }.toMap
    // Convert the full list of specified parameters
    val specifiedParametersMap: Map[String, ParameterValue] =
      specifiedParameters.view.mapValues(SpecifiedValue.apply).toMap
    // Get the deployment parameters that are present in the template
    val deployParametersInTemplate: Map[String, ParameterValue] =
      deployParameters
        .collect {
          case (key, value) if allRequiredParamNames.contains(key) =>
            key -> SpecifiedValue(value)
        }
    // Now combine them all together in increasing priority
    val parametersToProvide =
      existingParametersMap ++ specifiedParametersMap ++ deployParametersInTemplate

    // Next we need to check we have all the parameters we need and none that we don't recognise

    // Get the parameters that have defaults in the template
    val parametersWithTemplateDefaults =
      templateParameters.filter(_.default).map(_.key).toSet
    // Compute the set of parameters that we will neither provide nor have default values
    val missingParams =
      allRequiredParamNames -- parametersToProvide.keySet -- parametersWithTemplateDefaults

    // And the set of parameters that we think we should provide but don't exist
    val unknownParams = parametersToProvide.keySet -- allRequiredParamNames

    if (missingParams.nonEmpty) {
      Left(
        s"""Missing parameters for ${missingParams.toList.sorted.mkString(", ")}: all new parameters without a
           |default must be set when creating or updating stacks. Subsequent updates reuse existing parameters
           |where possible.""".stripMargin
      )
    } else if (unknownParams.nonEmpty) {
      Left(
        s"""User specified parameters that are not in template: ${unknownParams.toList.sorted
            .mkString(", ")}.
           | Ensure that you are not specifying a parameter directly or as an AMI parameter.
           |""".stripMargin
      )
    } else {
      Right(parametersToProvide)
    }
  }
}

object UpdateCloudFormationTask extends Loggable {
  sealed trait CloudFormationStackLookupStrategy
  case class LookupByName(cloudFormationStackName: String)
      extends CloudFormationStackLookupStrategy {
    override def toString = s"called $cloudFormationStackName"
  }
  object LookupByName {
    def apply(
        stack: Stack,
        stage: Stage,
        cfnStackName: String,
        prependStack: Boolean,
        appendStage: Boolean
    ): LookupByName = {
      val stackName = Some(stack.name).filter(_ => prependStack)
      val stageName = Some(stage.name).filter(_ => appendStage)
      val cloudFormationStackNameParts =
        Seq(stackName, Some(cfnStackName), stageName).flatten
      val fullCloudFormationStackName =
        cloudFormationStackNameParts.mkString("-")
      LookupByName(fullCloudFormationStackName)
    }
  }
  case class LookupByTags(tags: Map[String, String])
      extends CloudFormationStackLookupStrategy {
    override def toString = s"with tags $tags"
  }
  object LookupByTags {
    def apply(
        pkg: DeploymentPackage,
        target: DeployTarget,
        reporter: DeployReporter
    ): LookupByTags = {
      LookupByTags(
        Map(
          "Stage" -> target.parameters.stage.name,
          "Stack" -> target.stack.name,
          "App" -> pkg.pkgApp.name
        )
      )
    }
  }

  def processTemplate(
      stackName: String,
      templateBody: String,
      s3Client: S3Client,
      stsClient: StsClient,
      region: Region,
      reporter: DeployReporter
  ): Template = {
    val templateTooBigForSdkUpload = templateBody.length > 51200

    if (templateTooBigForSdkUpload) {
      val bucketName = S3.accountSpecificBucket(
        "riff-raff-cfn-templates",
        s3Client,
        stsClient,
        region,
        reporter,
        Some(1)
      )
      val keyName = s"$stackName-${System.currentTimeMillis()}"
      reporter.verbose(
        s"Uploading template as $keyName to S3 bucket $bucketName"
      )
      val request = PutObjectRequest
        .builder()
        .bucket(bucketName)
        .key(keyName)
        .build()
      s3Client.putObject(request, RequestBody.fromString(templateBody))
      val url: String =
        s"https://$bucketName.s3-${region.name}.amazonaws.com/$keyName"
      logger.info(s"Using template url $url to update the stack")
      TemplateUrl(url)
    } else {
      TemplateBody(templateBody)
    }
  }
}

case class UpdateAmiCloudFormationParameterTask(
    region: Region,
    cloudFormationStackLookupStrategy: CloudFormationStackLookupStrategy,
    amiParameterMap: Map[CfnParam, TagCriteria],
    latestImage: CfnParam => String => String => Map[String, String] => Option[
      String
    ],
    stage: Stage,
    stack: Stack
)(implicit val keyRing: KeyRing)
    extends Task
    with RetryCloudFormationUpdate {

  import UpdateCloudFormationTask._

  override def execute(resources: DeploymentResources, stopFlag: => Boolean) =
    if (!stopFlag) {
      CloudFormation.withCfnClient(keyRing, region, resources) { cfnClient =>
        val maybeCfStack = cloudFormationStackLookupStrategy match {
          case LookupByName(cloudFormationStackName) =>
            CloudFormation.describeStack(cloudFormationStackName, cfnClient)
          case LookupByTags(tags) =>
            CloudFormation.findStackByTags(tags, resources.reporter, cfnClient)
        }

        val cfStack = maybeCfStack.getOrElse {
          resources.reporter.fail(
            s"Could not find CloudFormation stack $cloudFormationStackLookupStrategy"
          )
        }

        val existingParameters: Map[String, ParameterValue] =
          cfStack.parameters.asScala
            .map(_.parameterKey -> UseExistingValue)
            .toMap

        val resolvedAmiParameters: Map[String, ParameterValue] =
          amiParameterMap.flatMap { case (parameterName, amiTags) =>
            if (
              !cfStack.parameters.asScala.exists(
                _.parameterKey == parameterName
              )
            ) {
              resources.reporter.fail(
                s"stack ${cfStack.stackName} does not have an $parameterName parameter to update"
              )
            }

            val currentAmi = cfStack.parameters.asScala
              .find(_.parameterKey == parameterName)
              .get
              .parameterValue
            val accountNumber = STS.withSTSclient(keyRing, region, resources)(
              STS.getAccountNumber
            )
            val maybeNewAmi =
              latestImage(parameterName)(accountNumber)(region.name)(amiTags)
            maybeNewAmi match {
              case Some(sameAmi) if currentAmi == sameAmi =>
                resources.reporter.info(
                  s"Current AMI is the same as the resolved AMI for $parameterName ($sameAmi)"
                )
                None
              case Some(newAmi) =>
                resources.reporter.info(
                  s"Resolved AMI for $parameterName: $newAmi"
                )
                Some(parameterName -> SpecifiedValue(newAmi))
              case None =>
                val tagsStr =
                  amiTags.map { case (k, v) => s"$k: $v" }.mkString(", ")
                resources.reporter.fail(
                  s"Failed to resolve AMI for ${cfStack.stackName} parameter $parameterName with tags: $tagsStr"
                )
            }
          }

        if (resolvedAmiParameters.nonEmpty) {
          val newParameters = existingParameters ++ resolvedAmiParameters
          resources.reporter.info(
            s"Updating cloudformation stack params: $newParameters"
          )
          val maybeExecutionRole = CloudFormation.getExecutionRole(keyRing)
          maybeExecutionRole.foreach(role =>
            resources.reporter.verbose(s"Using execution role $role")
          )
          updateWithRetry(resources.reporter, stopFlag) {
            CloudFormation.updateStackParams(
              cfStack.stackName,
              newParameters,
              maybeExecutionRole,
              cfnClient
            )
          }
        } else {
          resources.reporter.info(
            s"All AMIs the same as current AMIs. No update to perform."
          )
        }
      }
    }

  def description = {
    val components = amiParameterMap
      .map { case (name, tags) => s"$name to latest AMI with tags $tags" }
      .mkString(", ")
    s"Update $components in CloudFormation stack: $cloudFormationStackLookupStrategy"
  }
}

class CheckUpdateEventsTask(
    region: Region,
    stackLookupStrategy: CloudFormationStackLookupStrategy
)(implicit val keyRing: KeyRing)
    extends Task
    with Loggable {

  import UpdateCloudFormationTask._

  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    CloudFormation.withCfnClient(keyRing, region, resources) { cfnClient =>
      val stackName = stackLookupStrategy match {
        case LookupByName(name) => name
        case strategy @ LookupByTags(tags) =>
          val stack = CloudFormation
            .findStackByTags(tags, resources.reporter, cfnClient)
            .getOrElse(
              resources.reporter
                .fail(s"Could not find CloudFormation stack $strategy")
            )
          stack.stackName
      }

      new CloudFormationStackEventPoller(
        stackName,
        cfnClient,
        resources,
        stopFlag
      ).check(None)
    }
  }

  def description = s"Checking events on update for stack $stackLookupStrategy"
}

class CloudFormationStackEventPoller(
    stackName: String,
    cfnClient: CloudFormationClient,
    resources: DeploymentResources,
    stopFlag: => Boolean
) extends Loggable {
  private[this] def reportEvent(
      reporter: DeployReporter,
      e: StackEvent
  ): Unit = {
    reporter.info(
      s"${e.logicalResourceId} (${e.resourceType}): ${e.resourceStatusAsString}"
    )
    if (e.resourceStatusReason != null) reporter.verbose(e.resourceStatusReason)
  }
  private[this] def isStackEvent(stackName: String)(e: StackEvent): Boolean =
    e.resourceType == "AWS::CloudFormation::Stack" && e.logicalResourceId == stackName

  private[this] def updateStart(stackName: String)(e: StackEvent): Boolean =
    isStackEvent(stackName)(
      e
    ) && (e.resourceStatusAsString == "UPDATE_IN_PROGRESS" || e.resourceStatusAsString == "CREATE_IN_PROGRESS")

  private[this] def updateComplete(stackName: String)(e: StackEvent): Boolean =
    isStackEvent(stackName)(
      e
    ) && (e.resourceStatusAsString == "UPDATE_COMPLETE" || e.resourceStatusAsString == "CREATE_COMPLETE")

  private[this] def updateFailed(e: StackEvent): Boolean = {
    val failed = e.resourceStatusAsString.contains(
      "FAILED"
    ) || e.resourceStatusAsString.contains("ROLLBACK")
    logger.debug(s"${e.resourceStatusAsString} - failed = $failed")
    failed
  }

  private[this] def fail(reporter: DeployReporter, e: StackEvent): Unit =
    reporter.fail(
      s"""${e.logicalResourceId}(${e.resourceType}}: ${e.resourceStatusAsString}
       |${e.resourceStatusReason}""".stripMargin
    )

  @tailrec
  final def check(
      lastSeenEvent: Option[StackEvent],
      additionalChecks: () => Boolean = () => true
  ): Unit = {
    val result = CloudFormation.describeStackEvents(stackName, cfnClient)
    val events = result.stackEvents.asScala

    lastSeenEvent match {
      case None =>
        events.find(updateStart(stackName)) match {
          case None =>
            resources.reporter.fail(
              s"No events found at all for stack $stackName"
            )
          case Some(e) =>
            val age = Duration.between(e.timestamp(), Instant.now())
            if (age > ofSeconds(30)) {
              resources.reporter.verbose(
                "No recent IN_PROGRESS events found (nothing within last 30 seconds)"
              )
            } else {
              reportEvent(resources.reporter, e)
              check(Some(e), additionalChecks)
            }
        }
      case Some(event) =>
        val newEvents = events.takeWhile(_.timestamp.isAfter(event.timestamp))
        newEvents.reverse.foreach(reportEvent(resources.reporter, _))
        newEvents.filter(updateFailed).foreach(fail(resources.reporter, _))

        val complete =
          newEvents.exists(updateComplete(stackName)) && additionalChecks()
        if (!complete && !stopFlag) {
          Thread.sleep(5000)
          check(Some(newEvents.headOption.getOrElse(event)), additionalChecks)
        }
    }
  }
}
