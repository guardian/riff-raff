package magenta.tasks

import com.amazonaws.AmazonServiceException
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import magenta.{DeployLogger, KeyRing, Stack, Stage}
import com.amazonaws.regions.Regions
import com.amazonaws.services.cloudformation.AmazonCloudFormationAsyncClient
import com.amazonaws.services.cloudformation.model._
import org.joda.time.{DateTime, Duration}

import scalax.file.Path
import collection.convert.wrapAsScala._

object UpdateCloudFormationTask {
  sealed trait ParameterValue
  case class SpecifiedValue(value: String) extends ParameterValue
  case object UseExistingValue extends ParameterValue
  case class TemplateParameter(key:String, default:Boolean)

  def combineParameters(stack: Stack, stage: Stage, templateParameters: Seq[TemplateParameter], parameters: Map[String, String], amiParam: Option[(String, String)]): Map[String, ParameterValue] = {
    def addParametersIfInTemplate(params: Map[String, ParameterValue])(nameValues: Iterable[(String, String)]): Map[String, ParameterValue] = {
      nameValues.foldLeft(params) {
        case (completeParams, (name, value)) if templateParameters.exists(_.key == name) => completeParams + (name -> SpecifiedValue(value))
        case (completeParams, _) => completeParams
      }
    }

    val requiredParams: Map[String, ParameterValue] = templateParameters.filterNot(_.default).map(_.key -> UseExistingValue).toMap
    val userAndDefaultParams = requiredParams ++ (parameters ++ amiParam).mapValues(SpecifiedValue.apply)

    addParametersIfInTemplate(userAndDefaultParams)(
      Seq("Stage" -> stage.name) ++ stack.nameOption.map(name => "Stack" -> name)
    )
  }
}

object JsonConverter {

  /**
    * Return the template's content as JSON,
    * converting it from YAML if necessary.
    */
  def convert(template: Path)(implicit logger: DeployLogger): String = template.extension match {
    case Some("yml") | Some("yaml") =>
      logger.info(s"Converting ${template.name} from YAML to JSON")
      val tree = new ObjectMapper(new YAMLFactory()).readTree(template.string)
      new ObjectMapper()
        .writer(new DefaultPrettyPrinter().withoutSpacesInObjectEntries())
        .writeValueAsString(tree)
    case _ =>
      // Assume it's already in JSON, so just return the file contents
      template.string
  }

}

case class UpdateCloudFormationTask(
  cloudFormationStackName: String,
  template: Path,
  userParameters: Map[String, String],
  amiParamName: String,
  amiTags: Map[String, String],
  latestImage: String => Map[String,String] => Option[String],
  stage: Stage,
  stack: Stack,
  createStackIfAbsent:Boolean)(implicit val keyRing: KeyRing) extends Task {

  import UpdateCloudFormationTask._

  override def execute(logger: DeployLogger, stopFlag: => Boolean) = if (!stopFlag) {
    val templateJson = JsonConverter.convert(template)(logger)
    val templateParameters = CloudFormation.validateTemplate(templateJson).getParameters
      .map(tp => TemplateParameter(tp.getParameterKey, Option(tp.getDefaultValue).isDefined))

    val amiParam: Option[(String, String)] = if (amiTags.nonEmpty) {
      latestImage(CloudFormation.region.name)(amiTags).map(amiParamName -> _)
    } else None

    val parameters: Map[String, ParameterValue] = combineParameters(stack, stage, templateParameters, userParameters, amiParam)

    logger.info(s"Parameters: $parameters")

    if (CloudFormation.describeStack(cloudFormationStackName).isDefined)
      try {
        CloudFormation.updateStack(cloudFormationStackName, templateJson, parameters)
      } catch {
        case ase:AmazonServiceException if ase.getMessage contains "No updates are to be performed." =>
          logger.info("Cloudformation update has no changes to template or parameters")
        case ase:AmazonServiceException if ase.getMessage contains "Template format error: JSON not well-formed" =>
          logger.info(s"Cloudformation update failed with the following template content:\n$templateJson")
          throw ase
      }
    else if (createStackIfAbsent) {
      logger.info(s"Stack $cloudFormationStackName doesn't exist. Creating stack.")
      CloudFormation.createStack(logger, cloudFormationStackName, templateJson, parameters)
    } else {
      logger.fail(s"Stack $cloudFormationStackName doesn't exist and createStackIfAbsent is false")
    }
  }

  def description = s"Updating CloudFormation stack: $cloudFormationStackName with ${template.name}"
  def verbose = description
}

case class UpdateAmiCloudFormationParameterTask(
  cloudFormationStackName: String,
  amiParameter: String,
  amiTags: Map[String, String],
  latestImage: String => Map[String, String] => Option[String],
  stage: Stage,
  stack: Stack)(implicit val keyRing: KeyRing) extends Task {

  import UpdateCloudFormationTask._

  override def execute(logger: DeployLogger, stopFlag: => Boolean) = if (!stopFlag) {
    val (existingParameters, currentAmi) = CloudFormation.describeStack(cloudFormationStackName) match {
      case Some(cfStack) if cfStack.getParameters.exists(_.getParameterKey == amiParameter) =>
        (cfStack.getParameters.map(_.getParameterKey -> UseExistingValue).toMap, cfStack.getParameters.find(_.getParameterKey == amiParameter).get.getParameterValue)
      case Some(_) =>
        logger.fail(s"stack $cloudFormationStackName does not have an $amiParameter parameter to update")
      case None =>
        logger.fail(s"Could not find CloudFormation stack $cloudFormationStackName")
    }

    latestImage(CloudFormation.region.name)(amiTags) match {
      case Some(sameAmi) if currentAmi == sameAmi =>
        logger.info(s"Current AMI is the same as the resolved AMI ($sameAmi). No update to perform.")
      case Some(ami) =>
        logger.info(s"Resolved AMI: $ami")
        val parameters = existingParameters + (amiParameter -> SpecifiedValue(ami))
        logger.info(s"Updating cloudformation stack params: $parameters")
        CloudFormation.updateStackParams(cloudFormationStackName, parameters)
      case None =>
        val tagsStr = amiTags.map { case (k, v) => s"$k: $v" }.mkString(", ")
        logger.fail(s"Failed to resolve AMI for $cloudFormationStackName with tags: $tagsStr")
    }
  }

  def description = s"Update $amiParameter to latest AMI with tags $amiTags in CloudFormation stack: $cloudFormationStackName"
  def verbose = description
}

case class CheckUpdateEventsTask(stackName: String)(implicit val keyRing: KeyRing) extends Task {

  override def execute(logger: DeployLogger, stopFlag: => Boolean): Unit = {
    import StackEvent._

    def check(lastSeenEvent: Option[StackEvent]): Unit = {
      val result = CloudFormation.describeStackEvents(stackName)
      val events = result.getStackEvents

      lastSeenEvent match {
        case None => events.find(updateStart) foreach (e => {
          val age = new Duration(new DateTime(e.getTimestamp), new DateTime()).getStandardSeconds
          if (age > 30) {
            logger.verbose("No recent IN_PROGRESS events found (nothing within last 30 seconds)")
          } else {
            reportEvent(logger, e)
            check(Some(e))
          }
        })
        case Some(event) => {
          val newEvents = events.takeWhile(_.getTimestamp.after(event.getTimestamp))
          newEvents.reverse.foreach(reportEvent(logger, _))

          if (!newEvents.exists(e => updateComplete(e) || failed(e)) && !stopFlag) {
            Thread.sleep(5000)
            check(Some(newEvents.headOption.getOrElse(event)))
          }
          newEvents.filter(failed).foreach(fail(logger, _))
        }
      }
    }
    check(None)
  }

  object StackEvent {
    def reportEvent(logger: DeployLogger, e: StackEvent): Unit = {
      logger.info(s"${e.getLogicalResourceId} (${e.getResourceType}): ${e.getResourceStatus}")
      if (e.getResourceStatusReason != null) logger.verbose(e.getResourceStatusReason)
    }
    def isStackEvent(e: StackEvent): Boolean =
      e.getResourceType == "AWS::CloudFormation::Stack" && e.getLogicalResourceId == stackName
    def updateStart(e: StackEvent): Boolean =
      isStackEvent(e) && (e.getResourceStatus == "UPDATE_IN_PROGRESS" || e.getResourceStatus == "CREATE_IN_PROGRESS")
    def updateComplete(e: StackEvent): Boolean =
      isStackEvent(e) && (e.getResourceStatus == "UPDATE_COMPLETE" || e.getResourceStatus == "CREATE_COMPLETE")

    def failed(e: StackEvent): Boolean = e.getResourceStatus.contains("FAILED")

    def fail(logger: DeployLogger, e: StackEvent): Unit = logger.fail(
      s"""${e.getLogicalResourceId}(${e.getResourceType}}: ${e.getResourceStatus}
            |${e.getResourceStatusReason}""".stripMargin)
  }

  def description = s"Checking events on update for: $stackName"
  def verbose = description
}

trait CloudFormation extends AWS {
  import UpdateCloudFormationTask._
  val CAPABILITY_IAM = "CAPABILITY_IAM"

  val region = Regions.EU_WEST_1
  def client(implicit keyRing: KeyRing) = {
    com.amazonaws.regions.Region.getRegion(region).createClient(
      classOf[AmazonCloudFormationAsyncClient], provider(keyRing), null
    )
  }

  def validateTemplate(templateBody: String)(implicit keyRing: KeyRing) =
    client.validateTemplate(new ValidateTemplateRequest().withTemplateBody(templateBody))

  def updateStack(name: String, templateBody: String, parameters: Map[String, ParameterValue])(implicit keyRing: KeyRing) =
    client.updateStack(
      new UpdateStackRequest().withStackName(name).withTemplateBody(templateBody).withCapabilities(CAPABILITY_IAM).withParameters(
        parameters map {
          case (k, SpecifiedValue(v)) => new Parameter().withParameterKey(k).withParameterValue(v)
          case (k, UseExistingValue) => new Parameter().withParameterKey(k).withUsePreviousValue(true)
        } toSeq: _*
      )
    )

  def updateStackParams(name: String, parameters: Map[String, ParameterValue])(implicit keyRing: KeyRing) =
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

  def createStack(logger: DeployLogger, name: String, templateBody: String, parameters: Map[String, ParameterValue])(implicit keyRing: KeyRing) =
    client.createStack(
      new CreateStackRequest().withStackName(name).withTemplateBody(templateBody).withCapabilities(CAPABILITY_IAM).withParameters(
        parameters map {
          case (k, SpecifiedValue(v)) => new Parameter().withParameterKey(k).withParameterValue(v)
          case (k, UseExistingValue) => logger.fail(s"Missing parameter value for parameter $k: all must be specified when creating a stack. Subsequent updates will reuse existing parameter values where possible.")
         } toSeq: _*
      )
    )

  def describeStack(name: String)(implicit keyRing:KeyRing) =
    client.describeStacks(
      new DescribeStacksRequest()
    ).getStacks.find(_.getStackName == name)

  def describeStackEvents(name: String)(implicit keyRing: KeyRing) =
    client.describeStackEvents(
      new DescribeStackEventsRequest().withStackName(name)
    )
}

object CloudFormation extends CloudFormation
