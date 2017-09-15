package magenta.tasks

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.cloudformation.model.StackEvent
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.securitytoken.AWSSecurityTokenService
import magenta.artifact.S3Path
import magenta.deployment_type.CloudFormationDeploymentTypeParameters._
import magenta.tasks.CloudFormation._
import magenta.tasks.UpdateCloudFormationTask.CloudFormationStackLookupStrategy
import magenta.{DeployReporter, DeployTarget, DeploymentPackage, KeyRing, Region, Stack, Stage}
import org.joda.time.{DateTime, Duration}

import scala.collection.convert.wrapAsScala._

object UpdateCloudFormationTask {
  case class TemplateParameter(key:String, default:Boolean)

  sealed trait CloudFormationStackLookupStrategy
  case class LookupByName(cloudFormationStackName: String) extends CloudFormationStackLookupStrategy {
    override def toString = s"called $cloudFormationStackName"
  }
  object LookupByName {
    def apply(stack: Stack, stage: Stage, cfnStackName: String, prependStack: Boolean, appendStage: Boolean): LookupByName = {
      val stackName = stack.nameOption.filter(_ => prependStack)
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
      val lookupByTags = for {
        stack <- target.stack.nameOption
        app <- pkg.pkgApps.map(_.name).headOption if pkg.pkgApps.size == 1
        stage = target.parameters.stage.name
      } yield LookupByTags(Map(
        "Stage" -> stage,
        "Stack" -> stack,
        "App" -> app
      ))

      lookupByTags.getOrElse(reporter.fail(
        s"Tag lookup of cloudformation stacks can only be used when the configuration specifies a stack and exactly one app - you have stack=${target.stack.nameOption} and apps=${pkg.apps.map(_.name).mkString(",")}"
      ))
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

    addParametersIfInTemplate(userAndDefaultParams)(
      Seq("Stage" -> stage.name) ++ stack.nameOption.map("Stack" -> _)
    )
  }

  def nameToCallNewStack(strategy: CloudFormationStackLookupStrategy): String = {
    val intrinsicKeyOrder = List("Stack", "Stage", "App")
    strategy match {
      case LookupByName(name) => name
      case LookupByTags(tags) =>
        val orderedTags = tags.toList.sortBy{ case (key, value) =>
          // order by the intrinsic ordering and then alphabetically for keys we don't know
          val order = intrinsicKeyOrder.indexOf(key)
          val intrinsicOrdering = if (order == -1) Int.MaxValue else order
          (intrinsicOrdering, key)
        }
        orderedTags.map{ case (key, value) => value }.mkString("-")
    }
  }

  def processTemplate(stackName: String, templateBody: String, s3Client: AmazonS3, stsClient: AWSSecurityTokenService,
    region: Region, alwaysUploadToS3: Boolean, reporter: DeployReporter): Template = {
    val templateTooBigForSdkUpload = templateBody.length > 51200

    if (alwaysUploadToS3 || templateTooBigForSdkUpload) {
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

case class UpdateCloudFormationTask(
  region: Region,
  cloudFormationStackLookupStrategy: CloudFormationStackLookupStrategy,
  templatePath: S3Path,
  userParameters: Map[String, String],
  amiParameterMap: Map[CfnParam, TagCriteria],
  latestImage: String => Map[String,String] => Option[String],
  stage: Stage,
  stack: Stack,
  createStackIfAbsent:Boolean,
  alwaysUploadToS3:Boolean)(implicit val keyRing: KeyRing, artifactClient: AmazonS3) extends Task {

  import UpdateCloudFormationTask._

  override def execute(reporter: DeployReporter, stopFlag: => Boolean) = if (!stopFlag) {
    val cfnClient = CloudFormation.makeCfnClient(keyRing, region)
    val s3Client = S3.makeS3client(keyRing, region)
    val stsClient = STS.makeSTSclient(keyRing, region)

    val maybeCfStack = cloudFormationStackLookupStrategy match {
      case LookupByName(cloudFormationStackName) => CloudFormation.describeStack(cloudFormationStackName, cfnClient)
      case LookupByTags(tags) => CloudFormation.findStackByTags(tags, reporter, cfnClient)
    }

    val templateString = templatePath.fetchContentAsString.right.getOrElse(
      reporter.fail(s"Unable to locate cloudformation template s3://${templatePath.bucket}/${templatePath.key}")
    )

    val nameToCallStack = UpdateCloudFormationTask.nameToCallNewStack(cloudFormationStackLookupStrategy)

    val template = processTemplate(nameToCallStack, templateString, s3Client, stsClient, region, alwaysUploadToS3, reporter)

    val templateParameters = CloudFormation.validateTemplate(template, cfnClient).getParameters
      .map(tp => TemplateParameter(tp.getParameterKey, Option(tp.getDefaultValue).isDefined))

    val resolvedAmiParameters: Map[String, String] = amiParameterMap.flatMap { case (name, tags) =>
      val ami = latestImage(region.name)(tags)
      ami.map(name ->)
    }

    val parameters: Map[String, ParameterValue] =
        combineParameters(stack, stage, templateParameters, userParameters ++ resolvedAmiParameters)

    reporter.info(s"Parameters: $parameters")

    maybeCfStack match {
      case Some(cloudFormationStackName) =>
        try {
          CloudFormation.updateStack(cloudFormationStackName.getStackName, template, parameters, cfnClient)
        } catch {
          case ase:AmazonServiceException if ase.getMessage contains "No updates are to be performed." =>
            reporter.info("Cloudformation update has no changes to template or parameters")
          case ase:AmazonServiceException if ase.getMessage contains "Template format error: JSON not well-formed" =>
            reporter.info(s"Cloudformation update failed with the following template content:\n$templateString")
            throw ase
        }
      case None =>
        if (createStackIfAbsent) {
          val stackTags = PartialFunction.condOpt(cloudFormationStackLookupStrategy){ case LookupByTags(tags) => tags }
          reporter.info(s"Stack $cloudFormationStackLookupStrategy doesn't exist. Creating stack using name $nameToCallStack.")
          CloudFormation.createStack(reporter, nameToCallStack, stackTags, template, parameters, cfnClient)
        } else {
          reporter.fail(s"Stack $cloudFormationStackLookupStrategy doesn't exist and createStackIfAbsent is false")
        }
    }
  }

  def description = s"Updating CloudFormation stack $cloudFormationStackLookupStrategy with ${templatePath.fileName}"
  def verbose = description
}

case class UpdateAmiCloudFormationParameterTask(
  region: Region,
  cloudFormationStackLookupStrategy: CloudFormationStackLookupStrategy,
  amiParameterMap: Map[CfnParam, TagCriteria],
  latestImage: String => Map[String, String] => Option[String],
  stage: Stage,
  stack: Stack)(implicit val keyRing: KeyRing) extends Task {

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

    val existingParameters: Map[String, ParameterValue] = cfStack.getParameters.map(_.getParameterKey -> UseExistingValue).toMap

    val resolvedAmiParameters: Map[String, ParameterValue] = amiParameterMap.flatMap { case(parameterName, amiTags) =>
      if (!cfStack.getParameters.exists(_.getParameterKey == parameterName)) {
        reporter.fail(s"stack ${cfStack.getStackName} does not have an $parameterName parameter to update")
      }

      val currentAmi = cfStack.getParameters.find(_.getParameterKey == parameterName).get.getParameterValue
      val maybeNewAmi = latestImage(region.name)(amiTags)
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
      CloudFormation.updateStackParams(cfStack.getStackName, newParameters, cfnClient)
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

case class CheckUpdateEventsTask(
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
      val events = result.getStackEvents

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