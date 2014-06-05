package magenta.tasks

import magenta.{MessageBroker, Stage, KeyRing}
import com.amazonaws.regions.Regions
import com.amazonaws.services.cloudformation.AmazonCloudFormationAsyncClient
import com.amazonaws.services.cloudformation.model.{StackEvent, DescribeStackEventsRequest, Parameter, UpdateStackRequest}
import scalax.file.Path
import collection.convert.wrapAsScala._

case class UpdateCloudFormationTask(stackName: String, template: Path, parameters: Map[String, String], stage: Stage)
                                   (implicit val keyRing: KeyRing) extends Task {
  def execute(stopFlag: => Boolean) = if (!stopFlag) {
    CloudFormation.updateStack(stackName, template.string, parameters + ("Stage" -> stage.name))
  }

  def description = s"Updating CloudFormation stack: $stackName with ${template.name}"
  def verbose = description
}

case class CheckUpdateEventsTask(stackName: String)(implicit val keyRing: KeyRing) extends Task {

  def execute(stopFlag: => Boolean): Unit = {
    def reportEvent(e: StackEvent): Unit = {
      MessageBroker.info(s"${e.getLogicalResourceId} (${e.getResourceType}): ${e.getResourceStatus}")
      if (e.getResourceStatusReason != null) MessageBroker.verbose(e.getResourceStatusReason)
    }
    def updateStart(e: StackEvent): Boolean = e.getResourceStatus == "UPDATE_IN_PROGRESS" &&
      e.getResourceType == "AWS::CloudFormation::Stack"
    def updateComplete(e: StackEvent): Boolean = e.getResourceStatus == "UPDATE_COMPLETE" &&
      e.getResourceType == "AWS::CloudFormation::Stack"
    def fail(e: StackEvent): Unit = MessageBroker.fail(
        s"""${e.getLogicalResourceId}(${e.getResourceType}}: ${e.getResourceStatus}
            |${e.getResourceStatusReason}""".stripMargin)

    def check(lastSeenEvent: Option[StackEvent]): Unit = {
      val result = CloudFormation.describeStackEvents(stackName)
      val events = result.getStackEvents

      lastSeenEvent match {
        case None => events.find(updateStart) foreach (e => {
          reportEvent(e)
          check(Some(e))
        })
        case Some(event) => {
          val newEvents = events.filter(_.getTimestamp.after(event.getTimestamp))
          newEvents.reverse.foreach(reportEvent)

          newEvents.headOption match {
            case Some(latest) => {
              if (latest.getResourceStatus.contains("FAILED")) fail(latest)
              else if (!updateComplete(latest)) {
                Thread.sleep(5000)
                check(Some(latest))
              }
            }
            case None => {
              Thread.sleep(5000)
              check(Some(event))
            }
          }
        }
      }
    }
    check(None)
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
  def updateStack(name: String, templateBody: String, parameters: Map[String, String])(implicit keyRing: KeyRing) =
    client.updateStack(
      new UpdateStackRequest().withStackName(name).withTemplateBody(templateBody).withCapabilities("CAPABILITY_IAM").withParameters(
        parameters map {
          case (k, v) => new Parameter().withParameterKey(k).withParameterValue(v)
        } toSeq: _*
      )
    )

  def describeStackEvents(name: String)(implicit keyRing: KeyRing) =
    client.describeStackEvents(
      new DescribeStackEventsRequest().withStackName(name)
    )
}

object CloudFormation extends CloudFormation
