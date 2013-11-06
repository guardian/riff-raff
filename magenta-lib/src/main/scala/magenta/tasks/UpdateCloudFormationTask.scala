package magenta.tasks

import magenta.KeyRing
import com.amazonaws.regions.Regions
import com.amazonaws.services.cloudformation.AmazonCloudFormationAsyncClient
import com.amazonaws.services.cloudformation.model.{Parameter, UpdateStackRequest}
import scalax.file.Path

case class UpdateCloudFormationTask(stackName: String, template: Path) extends Task {
  def execute(credentials: KeyRing, stopFlag: => Boolean) = if (!stopFlag) {
    CloudFormation.updateStack(stackName, template.string)(credentials)
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
  def updateStack(name: String, templateBody: String)(implicit keyRing: KeyRing) = client.updateStack(
    new UpdateStackRequest().withStackName(name).withTemplateBody(templateBody).withCapabilities("CAPABILITY_IAM")
      .withParameters(new Parameter().withParameterKey("").withParameterValue(""))
  )
}

object CloudFormation extends CloudFormation
