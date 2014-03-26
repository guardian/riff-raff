package magenta.tasks

import magenta.{Stage, KeyRing}
import com.amazonaws.regions.Regions
import com.amazonaws.services.cloudformation.AmazonCloudFormationAsyncClient
import com.amazonaws.services.cloudformation.model.{Parameter, UpdateStackRequest}
import scalax.file.Path

case class UpdateCloudFormationTask(stackName: String, template: Path, parameters: Map[String, String], stage: Stage)
                                   (implicit val keyRing: KeyRing) extends Task {
  def execute(stopFlag: => Boolean) = if (!stopFlag) {
    CloudFormation.updateStack(stackName, template.string, parameters + ("Stage" -> stage.name))
  }

  def description = s"Updating CloudFormation stack: $stackName with ${template.name}"
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
}

object CloudFormation extends CloudFormation
