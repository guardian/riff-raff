package magenta.tasks

import com.gu.management.Loggable
import magenta.deployment_type.CloudFormationDeploymentTypeParameters._
import magenta.tasks.CloudFormation._
import magenta.tasks.UpdateCloudFormationTask.{CloudFormationStackLookupStrategy, LookupByName, LookupByTags, TemplateParameter}
import magenta.{Build, DeployReporter, DeployTarget, DeploymentPackage, KeyRing, Region, Stack, Stage}
import org.joda.time.{DateTime, Duration}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.{ChangeSetType, CloudFormationException, Parameter, StackEvent}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.sts.StsClient

import scala.annotation.tailrec
import scala.collection.JavaConverters._

/**
  * A simple trait to aid with attempting an update multiple times in the case that an update is already running.
  */
trait RetryCloudFormationUpdate {
  def duration: Long = 15 * 60 * 1000 // wait fifteen minutes
  def calculateSleepTime(currentAttempt: Int): Long = 30 * 1000 // sleep 30 seconds

  def updateWithRetry[T](reporter: DeployReporter, stopFlag: => Boolean)(theUpdate: => T): Option[T] = {
    val expiry = System.currentTimeMillis() + duration

    def updateAttempt(currentAttempt: Int): Option[T] = {
      try {
        Some(theUpdate)
      } catch {
        // this isn't great, but it seems to be the best that we can realistically do
        case e: CloudFormationException if e.awsErrorDetails.errorMessage.matches("^Stack:.* is in [A-Z_]* state and can not be updated.") =>
          if (stopFlag) {
            reporter.info("Abandoning remaining checks as stop flag has been set")
            None
          } else {
            val remainingTime = expiry - System.currentTimeMillis()
            if (remainingTime > 0) {
              val sleepyTime = calculateSleepTime(currentAttempt)
              reporter.verbose(f"Another update is running against this cloudformation stack, waiting for it to finish (tried $currentAttempt%s, will try again in ${sleepyTime.toFloat/1000}%.1f, will give up in ${remainingTime.toFloat/1000}%.1f)")
              Thread.sleep(sleepyTime)
              updateAttempt(currentAttempt + 1)
            } else {
              reporter.fail(s"Update is still running after $duration milliseconds (tried $currentAttempt times) - aborting")
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

class CloudFormationStackMetadata(val strategy: CloudFormationStackLookupStrategy, val changeSetName: String, createStackIfAbsent: Boolean) {
  import CloudFormationStackMetadata._

  def lookup(reporter: DeployReporter, cfnClient: CloudFormationClient): (String, ChangeSetType) = {
    val existingStack = strategy match {
      case LookupByName(name) => CloudFormation.describeStack(name, cfnClient)
      case LookupByTags(tags) => CloudFormation.findStackByTags(tags, reporter, cfnClient)
    }

    val stackName = existingStack.map(_.stackName).getOrElse(getNewStackName(strategy))
    val changeSetType = getChangeSetType(stackName, existingStack.nonEmpty, createStackIfAbsent, reporter)

    (stackName, changeSetType)
  }
}

object CloudFormationStackMetadata {
  def getChangeSetType(stackName: String, stackExists: Boolean, createStackIfAbsent: Boolean, reporter: DeployReporter): ChangeSetType = {
    if(!stackExists && !createStackIfAbsent) {
      reporter.fail(s"Stack $stackName doesn't exist and createStackIfAbsent is false")
    } else if(!stackExists) {
      ChangeSetType.CREATE
    } else {
      ChangeSetType.UPDATE
    }
  }

  def getNewStackName(strategy: CloudFormationStackLookupStrategy): String = strategy match {
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

class CloudFormationParameters(stack: Stack, stage: Stage, build: Build, region: Region,
                               val stackTags: Option[Map[String, String]], val userParameters: Map[String, String],
                               val amiParameterMap: Map[CfnParam, TagCriteria],
                               latestImage: String => String => Map[String,String] => Option[String]) {
  import CloudFormationParameters._

  def resolve(template: Template, accountNumber: String, changeSetType: ChangeSetType, reporter: DeployReporter, cfnClient: CloudFormationClient): Iterable[Parameter] = {
    val templateParameters = CloudFormation.validateTemplate(template, cfnClient).parameters.asScala
      .map(tp => TemplateParameter(tp.parameterKey, Option(tp.defaultValue).isDefined))

    val resolvedAmiParameters: Map[String, String] = amiParameterMap.flatMap { case (name, tags) =>
      latestImage(accountNumber)(region.name)(tags).map(name -> _)
    }

    val combined = combineParameters(stack, stage, build, templateParameters, userParameters ++ resolvedAmiParameters)
    convertParameters(combined, changeSetType, reporter)
  }
}

object CloudFormationParameters {
  def convertParameters(parameters: Map[String, ParameterValue], tpe: ChangeSetType, reporter: DeployReporter): Iterable[Parameter] = {
    parameters map {
      case (k, SpecifiedValue(v)) =>
        Parameter.builder().parameterKey(k).parameterValue(v).build()

      case (k, UseExistingValue) if tpe == ChangeSetType.CREATE =>
        reporter.fail(s"Missing parameter value for parameter $k: all must be specified when creating a stack. Subsequent updates will reuse existing parameter values where possible.")

      case (k, UseExistingValue) =>
        Parameter.builder().parameterKey(k).usePreviousValue(true).build()
    }
  }

  def combineParameters(stack: Stack, stage: Stage, build: Build, templateParameters: Seq[TemplateParameter], parameters: Map[String, String]): Map[String, ParameterValue] = {
    def addParametersIfInTemplate(params: Map[String, ParameterValue])(nameValues: Iterable[(String, String)]): Map[String, ParameterValue] = {
      nameValues.foldLeft(params) {
        case (completeParams, (name, value)) if templateParameters.exists(_.key == name) => completeParams + (name -> SpecifiedValue(value))
        case (completeParams, _) => completeParams
      }
    }

    val requiredParams: Map[String, ParameterValue] = templateParameters.filterNot(_.default).map(_.key -> UseExistingValue).toMap
    val userAndDefaultParams = requiredParams ++ parameters.mapValues(SpecifiedValue.apply)

    addParametersIfInTemplate(userAndDefaultParams)(Seq("Stage" -> stage.name, "Stack" -> stack.name, "BuildId" -> build.id))
  }
}

object UpdateCloudFormationTask extends Loggable {
  case class TemplateParameter(key:String, default:Boolean)

  sealed trait CloudFormationStackLookupStrategy
  case class LookupByName(cloudFormationStackName: String) extends CloudFormationStackLookupStrategy {
    override def toString = s"called $cloudFormationStackName"
  }
  object LookupByName {
    def apply(stack: Stack, stage: Stage, cfnStackName: String, prependStack: Boolean, appendStage: Boolean): LookupByName = {
      val stackName = Some(stack.name).filter(_ => prependStack)
      val stageName = Some(stage.name).filter(_ => appendStage)
      val cloudFormationStackNameParts = Seq(stackName, Some(cfnStackName), stageName).flatten
      val fullCloudFormationStackName = cloudFormationStackNameParts.mkString("-")
      LookupByName(fullCloudFormationStackName)
    }
  }
  case class LookupByTags(tags: Map[String, String]) extends CloudFormationStackLookupStrategy {
    override def toString = s"with tags $tags"
  }
  object LookupByTags {
    def apply(pkg: DeploymentPackage, target: DeployTarget, reporter: DeployReporter): LookupByTags = {
      LookupByTags(Map(
        "Stage" -> target.parameters.stage.name,
        "Stack" -> target.stack.name,
        "App" -> pkg.pkgApp.name
      ))
    }
  }

  def processTemplate(stackName: String, templateBody: String, s3Client: S3Client, stsClient: StsClient,
                      region: Region, reporter: DeployReporter): Template = {
    val templateTooBigForSdkUpload = templateBody.length > 51200

    if (templateTooBigForSdkUpload) {
      val bucketName = S3.accountSpecificBucket("riff-raff-cfn-templates", s3Client, stsClient, region, reporter, Some(1))
      val keyName = s"$stackName-${new DateTime().getMillis}"
      reporter.verbose(s"Uploading template as $keyName to S3 bucket $bucketName")
      val request = PutObjectRequest.builder()
        .bucket(bucketName)
        .key(keyName)
        .build()
      s3Client.putObject(request, RequestBody.fromString(templateBody))
      val url: String = s"https://$bucketName.s3-${region.name}.amazonaws.com/$keyName"
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
  latestImage: String => String => Map[String, String] => Option[String],
  stage: Stage,
  stack: Stack)(implicit val keyRing: KeyRing) extends Task with RetryCloudFormationUpdate {

  import UpdateCloudFormationTask._

  override def execute(reporter: DeployReporter, stopFlag: => Boolean) = if (!stopFlag) {
    CloudFormation.withCfnClient(keyRing, region){ cfnClient =>
      val maybeCfStack = cloudFormationStackLookupStrategy match {
        case LookupByName(cloudFormationStackName) => CloudFormation.describeStack(cloudFormationStackName, cfnClient)
        case LookupByTags(tags) => CloudFormation.findStackByTags(tags, reporter, cfnClient)
      }

      val cfStack = maybeCfStack.getOrElse{
        reporter.fail(s"Could not find CloudFormation stack $cloudFormationStackLookupStrategy")
      }

      val existingParameters: Map[String, ParameterValue] = cfStack.parameters.asScala.map(_.parameterKey -> UseExistingValue).toMap

      val resolvedAmiParameters: Map[String, ParameterValue] = amiParameterMap.flatMap { case(parameterName, amiTags) =>
        if (!cfStack.parameters.asScala.exists(_.parameterKey == parameterName)) {
          reporter.fail(s"stack ${cfStack.stackName} does not have an $parameterName parameter to update")
        }

        val currentAmi = cfStack.parameters.asScala.find(_.parameterKey == parameterName).get.parameterValue
        val accountNumber = STS.withSTSclient(keyRing, region)(STS.getAccountNumber)
        val maybeNewAmi = latestImage(accountNumber)(region.name)(amiTags)
        maybeNewAmi match {
          case Some(sameAmi) if currentAmi == sameAmi =>
            reporter.info(s"Current AMI is the same as the resolved AMI for $parameterName ($sameAmi)")
            None
          case Some(newAmi) =>
            reporter.info(s"Resolved AMI for $parameterName: $newAmi")
            Some(parameterName -> SpecifiedValue(newAmi))
          case None =>
            val tagsStr = amiTags.map { case (k, v) => s"$k: $v" }.mkString(", ")
            reporter.fail(s"Failed to resolve AMI for ${cfStack.stackName} parameter $parameterName with tags: $tagsStr")
        }
      }

      if (resolvedAmiParameters.nonEmpty) {
        val newParameters = existingParameters ++ resolvedAmiParameters
        reporter.info(s"Updating cloudformation stack params: $newParameters")
        updateWithRetry(reporter, stopFlag) {
          CloudFormation.updateStackParams(cfStack.stackName, newParameters, cfnClient)
        }
      } else {
        reporter.info(s"All AMIs the same as current AMIs. No update to perform.")
      }
    }
  }

  def description = {
    val components = amiParameterMap.map { case(name, tags) => s"$name to latest AMI with tags $tags"}.mkString(", ")
    s"Update $components in CloudFormation stack: $cloudFormationStackLookupStrategy"
  }
}

class CheckUpdateEventsTask(
  region: Region,
  stackLookupStrategy: CloudFormationStackLookupStrategy
)(implicit val keyRing: KeyRing) extends Task with Loggable {

  import UpdateCloudFormationTask._

  override def execute(reporter: DeployReporter, stopFlag: => Boolean): Unit = {
    CloudFormation.withCfnClient(keyRing, region) { cfnClient =>

      import StackEvent._

      val stackName = stackLookupStrategy match {
        case LookupByName(name) => name
        case strategy@LookupByTags(tags) =>
          val stack = CloudFormation.findStackByTags(tags, reporter, cfnClient)
            .getOrElse(reporter.fail(s"Could not find CloudFormation stack $strategy"))
          stack.stackName
      }

      @tailrec
      def check(lastSeenEvent: Option[StackEvent]): Unit = {
        val result = CloudFormation.describeStackEvents(stackName, cfnClient)
        val events = result.stackEvents.asScala

        lastSeenEvent match {
          case None =>
            events.find(updateStart(stackName)) match {
            case None =>
              reporter.fail(s"No events found at all for stack $stackName")
            case Some(e) =>
              val age = new Duration(new DateTime(e.timestamp().toEpochMilli), new DateTime()).getStandardSeconds
              if (age > 30) {
                reporter.verbose("No recent IN_PROGRESS events found (nothing within last 30 seconds)")
              } else {
                reportEvent(reporter, e)
                check(Some(e))
              }
            }
          case Some(event) =>
            val newEvents = events.takeWhile(_.timestamp.isAfter(event.timestamp))
            newEvents.reverse.foreach(reportEvent(reporter, _))

            newEvents.filter(updateFailed).foreach(fail(reporter, _))

          val complete = newEvents.exists(updateComplete(stackName))
          if (!complete && !stopFlag) {
              Thread.sleep(5000)
              check(Some(newEvents.headOption.getOrElse(event)))
            }

        }
      }

      check(None)
    }
  }

  object StackEvent {
    def reportEvent(reporter: DeployReporter, e: StackEvent): Unit = {
      reporter.info(s"${e.logicalResourceId} (${e.resourceType}): ${e.resourceStatus}")
      if (e.resourceStatusReason != null) reporter.verbose(e.resourceStatusReason)
    }
    def isStackEvent(stackName: String)(e: StackEvent): Boolean =
      e.resourceType == "AWS::CloudFormation::Stack" && e.logicalResourceId == stackName
    def updateStart(stackName: String)(e: StackEvent): Boolean =
      isStackEvent(stackName)(e) && (e.resourceStatus.toString == "UPDATE_IN_PROGRESS" || e.resourceStatus.toString == "CREATE_IN_PROGRESS")
    def updateComplete(stackName: String)(e: StackEvent): Boolean =
      isStackEvent(stackName)(e) && (e.resourceStatus.toString == "UPDATE_COMPLETE" || e.resourceStatus.toString == "CREATE_COMPLETE")

    def updateFailed(e: StackEvent): Boolean = {
      val failed = e.resourceStatus.toString.contains("FAILED") || e.resourceStatus.toString.contains("ROLLBACK")
      logger.debug(s"${e.resourceStatus} - failed = $failed")
      failed
    }

    def fail(reporter: DeployReporter, e: StackEvent): Unit = reporter.fail(
      s"""${e.logicalResourceId}(${e.resourceType}}: ${e.resourceStatus}
            |${e.resourceStatusReason}""".stripMargin)
  }

  def description = s"Checking events on update for stack $stackLookupStrategy"
}