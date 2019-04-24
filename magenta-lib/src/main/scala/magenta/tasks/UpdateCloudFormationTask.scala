package magenta.tasks

import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.model.{AmazonCloudFormationException, ChangeSetType, Parameter, StackEvent}
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.securitytoken.AWSSecurityTokenService
import magenta.deployment_type.CloudFormationDeploymentTypeParameters._
import magenta.tasks.CloudFormation._
import magenta.tasks.UpdateCloudFormationTask.{CloudFormationStackLookupStrategy, LookupByName, LookupByTags, TemplateParameter}
import magenta.{DeployReporter, DeployTarget, DeploymentPackage, KeyRing, Region, Stack, Stage}
import org.joda.time.{DateTime, Duration}

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
        case e:AmazonCloudFormationException if e.getErrorMessage.matches("^Stack:.* is in [A-Z_]* state and can not be updated.") =>
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
        case e:AmazonCloudFormationException =>
          // this might be useful for debugging in the future if a message is seen that we don't catch
          reporter.verbose(e.getErrorMessage)
          throw e
      }
    }
    updateAttempt(1)
  }
}

class CloudFormationStackMetadata(val strategy: CloudFormationStackLookupStrategy, val changeSetName: String, createStackIfAbsent: Boolean) {
  import CloudFormationStackMetadata._

  def lookup(reporter: DeployReporter, cfnClient: AmazonCloudFormation): (String, ChangeSetType) = {
    val existingStack = strategy match {
      case LookupByName(name) => CloudFormation.describeStack(name, cfnClient)
      case LookupByTags(tags) => CloudFormation.findStackByTags(tags, reporter, cfnClient)
    }

    val stackName = existingStack.map(_.getStackName).getOrElse(getNewStackName(strategy))
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

class CloudFormationParameters(stack: Stack, stage: Stage, region: Region,
                               val stackTags: Option[Map[String, String]], val userParameters: Map[String, String],
                               val amiParameterMap: Map[CfnParam, TagCriteria],
                               latestImage: String => String => Map[String,String] => Option[String]) {
  import CloudFormationParameters._

  def resolve(template: Template, accountNumber: String, changeSetType: ChangeSetType, reporter: DeployReporter, cfnClient: AmazonCloudFormation): Iterable[Parameter] = {
    val templateParameters = CloudFormation.validateTemplate(template, cfnClient).getParameters.asScala
      .map(tp => TemplateParameter(tp.getParameterKey, Option(tp.getDefaultValue).isDefined))

    val resolvedAmiParameters: Map[String, String] = amiParameterMap.flatMap { case (name, tags) =>
      latestImage(accountNumber)(region.name)(tags).map(name -> _)
    }

    val combined = combineParameters(stack, stage, templateParameters, userParameters ++ resolvedAmiParameters)
    convertParameters(combined, changeSetType, reporter)
  }
}

object CloudFormationParameters {
  def convertParameters(parameters: Map[String, ParameterValue], tpe: ChangeSetType, reporter: DeployReporter): Iterable[Parameter] = {
    parameters map {
      case (k, SpecifiedValue(v)) =>
        new Parameter().withParameterKey(k).withParameterValue(v)

      case (k, UseExistingValue) if tpe == ChangeSetType.CREATE =>
        reporter.fail(s"Missing parameter value for parameter $k: all must be specified when creating a stack. Subsequent updates will reuse existing parameter values where possible.")

      case (k, UseExistingValue) =>
        new Parameter().withParameterKey(k).withUsePreviousValue(true)
    }
  }

  def combineParameters(stack: Stack, stage: Stage, templateParameters: Seq[TemplateParameter], parameters: Map[String, String]): Map[String, ParameterValue] = {
    def addParametersIfInTemplate(params: Map[String, ParameterValue])(nameValues: Iterable[(String, String)]): Map[String, ParameterValue] = {
      nameValues.foldLeft(params) {
        case (completeParams, (name, value)) if templateParameters.exists(_.key == name) => completeParams + (name -> SpecifiedValue(value))
        case (completeParams, _) => completeParams
      }
    }

    val requiredParams: Map[String, ParameterValue] = templateParameters.filterNot(_.default).map(_.key -> UseExistingValue).toMap
    val userAndDefaultParams = requiredParams ++ parameters.mapValues(SpecifiedValue.apply)

    addParametersIfInTemplate(userAndDefaultParams)(Seq("Stage" -> stage.name, "Stack" -> stack.name))
  }
}

object UpdateCloudFormationTask {
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

  def processTemplate(stackName: String, templateBody: String, s3Client: AmazonS3, stsClient: AWSSecurityTokenService,
    region: Region, reporter: DeployReporter): Template = {
    val templateTooBigForSdkUpload = templateBody.length > 51200

    if (templateTooBigForSdkUpload) {
      val bucketName = S3.accountSpecificBucket("riff-raff-cfn-templates", s3Client, stsClient, region, reporter, Some(1))
      val keyName = s"$stackName-${new DateTime().getMillis}"
      reporter.verbose(s"Uploading template as $keyName to S3 bucket $bucketName")
      s3Client.putObject(bucketName, keyName, templateBody)
      val url = s3Client.getUrl(bucketName, keyName)
      TemplateUrl(url.toString)
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
    val cfnClient = CloudFormation.makeCfnClient(keyRing, region)

    val maybeCfStack = cloudFormationStackLookupStrategy match {
      case LookupByName(cloudFormationStackName) => CloudFormation.describeStack(cloudFormationStackName, cfnClient)
      case LookupByTags(tags) => CloudFormation.findStackByTags(tags, reporter, cfnClient)
    }

    val cfStack = maybeCfStack.getOrElse{
      reporter.fail(s"Could not find CloudFormation stack $cloudFormationStackLookupStrategy")
    }

    val existingParameters: Map[String, ParameterValue] = cfStack.getParameters.asScala.map(_.getParameterKey -> UseExistingValue).toMap

    val resolvedAmiParameters: Map[String, ParameterValue] = amiParameterMap.flatMap { case(parameterName, amiTags) =>
      if (!cfStack.getParameters.asScala.exists(_.getParameterKey == parameterName)) {
        reporter.fail(s"stack ${cfStack.getStackName} does not have an $parameterName parameter to update")
      }

      val currentAmi = cfStack.getParameters.asScala.find(_.getParameterKey == parameterName).get.getParameterValue
      val accountNumber = STS.getAccountNumber(STS.makeSTSclient(keyRing, region))
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
          reporter.fail(s"Failed to resolve AMI for ${cfStack.getStackName} parameter $parameterName with tags: $tagsStr")
      }
    }

    if (resolvedAmiParameters.nonEmpty) {
      val newParameters = existingParameters ++ resolvedAmiParameters
      reporter.info(s"Updating cloudformation stack params: $newParameters")
      updateWithRetry(reporter, stopFlag) {
        CloudFormation.updateStackParams(cfStack.getStackName, newParameters, cfnClient)
      }
    } else {
      reporter.info(s"All AMIs the same as current AMIs. No update to perform.")
    }
  }

  def description = {
    val components = amiParameterMap.map { case(name, tags) => s"$name to latest AMI with tags $tags"}.mkString(", ")
    s"Update $components in CloudFormation stack: $cloudFormationStackLookupStrategy"
  }
  def verbose = description
}

class CheckUpdateEventsTask(
  region: Region,
  stackLookupStrategy: CloudFormationStackLookupStrategy
)(implicit val keyRing: KeyRing) extends Task {

  import UpdateCloudFormationTask._

  override def execute(reporter: DeployReporter, stopFlag: => Boolean): Unit = {
    val cfnClient = CloudFormation.makeCfnClient(keyRing, region)

    import StackEvent._

    val stackName = stackLookupStrategy match {
      case LookupByName(name) => name
      case strategy @ LookupByTags(tags) =>
        val stack = CloudFormation.findStackByTags(tags, reporter, cfnClient)
          .getOrElse(reporter.fail(s"Could not find CloudFormation stack $strategy"))
        stack.getStackName
    }

    def check(lastSeenEvent: Option[StackEvent]): Unit = {
      val result = CloudFormation.describeStackEvents(stackName, cfnClient)
      val events = result.getStackEvents.asScala

      lastSeenEvent match {
        case None =>
          events.find(updateStart(stackName)) foreach (e => {
            val age = new Duration(new DateTime(e.getTimestamp), new DateTime()).getStandardSeconds
            if (age > 30) {
              reporter.verbose("No recent IN_PROGRESS events found (nothing within last 30 seconds)")
            } else {
              reportEvent(reporter, e)
              check(Some(e))
            }
          })
        case Some(event) =>
          val newEvents = events.takeWhile(_.getTimestamp.after(event.getTimestamp))
          newEvents.reverse.foreach(reportEvent(reporter, _))

          if (!newEvents.exists(e => updateComplete(stackName)(e) || failed(e)) && !stopFlag) {
            Thread.sleep(5000)
            check(Some(newEvents.headOption.getOrElse(event)))
          }
          newEvents.filter(failed).foreach(fail(reporter, _))
      }
    }
    check(None)
  }

  object StackEvent {
    def reportEvent(reporter: DeployReporter, e: StackEvent): Unit = {
      reporter.info(s"${e.getLogicalResourceId} (${e.getResourceType}): ${e.getResourceStatus}")
      if (e.getResourceStatusReason != null) reporter.verbose(e.getResourceStatusReason)
    }
    def isStackEvent(stackName: String)(e: StackEvent): Boolean =
      e.getResourceType == "AWS::CloudFormation::Stack" && e.getLogicalResourceId == stackName
    def updateStart(stackName: String)(e: StackEvent): Boolean =
      isStackEvent(stackName)(e) && (e.getResourceStatus == "UPDATE_IN_PROGRESS" || e.getResourceStatus == "CREATE_IN_PROGRESS")
    def updateComplete(stackName: String)(e: StackEvent): Boolean =
      isStackEvent(stackName)(e) && (e.getResourceStatus == "UPDATE_COMPLETE" || e.getResourceStatus == "CREATE_COMPLETE")

    def failed(e: StackEvent): Boolean = e.getResourceStatus.contains("FAILED") || e.getResourceStatus.contains("ROLLBACK")

    def fail(reporter: DeployReporter, e: StackEvent): Unit = reporter.fail(
      s"""${e.getLogicalResourceId}(${e.getResourceType}}: ${e.getResourceStatus}
            |${e.getResourceStatusReason}""".stripMargin)
  }

  def description = s"Checking events on update for stack $stackLookupStrategy"
  def verbose = description
}