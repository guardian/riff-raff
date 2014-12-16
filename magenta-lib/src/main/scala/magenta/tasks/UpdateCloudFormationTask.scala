package magenta.tasks

import magenta.{MessageBroker, Stage, Stack, KeyRing}
import com.amazonaws.regions.Regions
import com.amazonaws.services.cloudformation.AmazonCloudFormationAsyncClient
import com.amazonaws.services.cloudformation.model._
import scalax.file.Path
import collection.convert.wrapAsScala._

case class UpdateCloudFormationTask(cloudFormationStackName: String, template: Path,
                                    parameters: Map[String, String], stage: Stage, stack: Stack,
                                    createStackIfAbsent:Boolean)
                                   (implicit val keyRing: KeyRing) extends Task {
  def execute(stopFlag: => Boolean) = if (!stopFlag) {
    val requiredParameters = CloudFormation.validateTemplate(template.string).getParameters

    def addParametersIfRequired(params: Map[String, String])(nameValues: Iterable[(String,  String)]): Map[String, String] = {
      nameValues.foldLeft(params) { case (completeParams, (name, value)) =>
        if (requiredParameters.map(_.getParameterKey).contains(name)) completeParams + (name -> value)
        else params
      }
    }

    val actualParameters = addParametersIfRequired(parameters)(
      Seq("Stage" -> stage.name) ++ stack.nameOption.map(name => "Stack" -> name))

    if (CloudFormation.describeStack(cloudFormationStackName).isDefined)
      CloudFormation.updateStack(cloudFormationStackName, template.string, actualParameters)
    else if (createStackIfAbsent) {
      MessageBroker.info(s"Stack $cloudFormationStackName doesn't exist. Creating stack.")
      CloudFormation.createStack(cloudFormationStackName, template.string, actualParameters)
    } else {
      MessageBroker.fail(s"Stack $cloudFormationStackName doesn't exist and createStackIfAbsent is false")
    }
  }

  def description = s"Updating CloudFormation stack: $cloudFormationStackName with ${template.name}"
  def verbose = description
}

case class CheckUpdateEventsTask(stackName: String)(implicit val keyRing: KeyRing) extends Task {

  def execute(stopFlag: => Boolean): Unit = {
    import StackEvent._

    def check(lastSeenEvent: Option[StackEvent]): Unit = {
      val result = CloudFormation.describeStackEvents(stackName)
      val events = result.getStackEvents

      lastSeenEvent match {
        case None => events.find(updateStart) foreach (e => {
          reportEvent(e)
          check(Some(e))
        })
        case Some(event) => {
          val newEvents = events.takeWhile(_.getTimestamp.after(event.getTimestamp))
          newEvents.reverse.foreach(reportEvent)

          if (!newEvents.exists(e => updateComplete(e) || failed(e)) && !stopFlag) {
            Thread.sleep(5000)
            check(Some(newEvents.headOption.getOrElse(event)))
          }
          newEvents.filter(failed).foreach(fail)
        }
      }
    }
    check(None)
  }

  object StackEvent {
    def reportEvent(e: StackEvent): Unit = {
      MessageBroker.info(s"${e.getLogicalResourceId} (${e.getResourceType}): ${e.getResourceStatus}")
      if (e.getResourceStatusReason != null) MessageBroker.verbose(e.getResourceStatusReason)
    }
    def updateStart(e: StackEvent): Boolean = e.getResourceStatus == "UPDATE_IN_PROGRESS" &&
      e.getResourceType == "AWS::CloudFormation::Stack"
    def updateComplete(e: StackEvent): Boolean = e.getResourceStatus == "UPDATE_COMPLETE" &&
      e.getResourceType == "AWS::CloudFormation::Stack"
    def failed(e: StackEvent): Boolean = e.getResourceStatus.contains("FAILED")

    def fail(e: StackEvent): Unit = MessageBroker.fail(
      s"""${e.getLogicalResourceId}(${e.getResourceType}}: ${e.getResourceStatus}
            |${e.getResourceStatusReason}""".stripMargin)
  }

  def description = s"Checking events on update for: $stackName"
  def verbose = description
}

trait CloudFormation extends AWS {
  def client(implicit keyRing: KeyRing) = {
    com.amazonaws.regions.Region.getRegion(Regions.EU_WEST_1).createClient(
      classOf[AmazonCloudFormationAsyncClient], provider(keyRing), null
    )
  }

  def validateTemplate(templateBody: String)(implicit keyRing: KeyRing) =
    client.validateTemplate(new ValidateTemplateRequest().withTemplateBody(templateBody))

  def updateStack(name: String, templateBody: String, parameters: Map[String, String])(implicit keyRing: KeyRing) =
    client.updateStack(
      new UpdateStackRequest().withStackName(name).withTemplateBody(templateBody).withCapabilities("CAPABILITY_IAM").withParameters(
        parameters map {
          case (k, v) => new Parameter().withParameterKey(k).withParameterValue(v)
        } toSeq: _*
      )
    )

  def createStack(name: String, templateBody: String, parameters: Map[String, String])(implicit keyRing: KeyRing) =
    client.createStack(
      new CreateStackRequest().withStackName(name).withTemplateBody(templateBody).withCapabilities("CAPABILITY_IAM").withParameters(
        parameters map {
          case (k, v) => new Parameter().withParameterKey(k).withParameterValue(v)
        } toSeq: _*
      )
    )

  def describeStack(name: String)(implicit keyRing:KeyRing) =
    client.describeStacks(
      new DescribeStacksRequest().withStackName(name)
    ).getStacks.headOption

  def describeStackEvents(name: String)(implicit keyRing: KeyRing) =
    client.describeStackEvents(
      new DescribeStackEventsRequest().withStackName(name)
    )
}

object CloudFormation extends CloudFormation
