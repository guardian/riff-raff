package magenta.tasks

import com.amazonaws.services.cloudformation.model._
import com.amazonaws.services.s3.AmazonS3
import magenta.artifact.S3Path
import magenta.tasks.UpdateCloudFormationTask._
import magenta.{DeployReporter, KeyRing, Region}

import scala.collection.JavaConverters._
import scala.util.{Success, Try}

class CreateChangeSetTask(
                           region: Region,
                           templatePath: S3Path,
                           stackLookup: CloudFormationStackMetadata,
                           val unresolvedParameters: CloudFormationParameters
)(implicit val keyRing: KeyRing, artifactClient: AmazonS3) extends Task {

  override def execute(reporter: DeployReporter, stopFlag: => Boolean) = if (!stopFlag) {
    val cfnClient = CloudFormation.makeCfnClient(keyRing, region)
    val s3Client = S3.makeS3client(keyRing, region)
    val stsClient = STS.makeSTSclient(keyRing, region)
    val accountNumber = STS.getAccountNumber(stsClient)

    val templateString = templatePath.fetchContentAsString.right.getOrElse(
      reporter.fail(s"Unable to locate cloudformation template s3://${templatePath.bucket}/${templatePath.key}")
    )

    val (stackName, changeSetType) = stackLookup.lookup(reporter, cfnClient)

    val template = processTemplate(stackName, templateString, s3Client, stsClient, region, reporter)
    val parameters = unresolvedParameters.resolve(template, accountNumber, changeSetType, reporter, cfnClient)

    reporter.info("Creating Cloudformation change set")
    reporter.info(s"Stack name: $stackName")
    reporter.info(s"Change set name: ${stackLookup.changeSetName}")
    reporter.info(s"Parameters: $parameters")

    CloudFormation.createChangeSet(reporter, stackLookup.changeSetName, changeSetType, stackName, unresolvedParameters.stackTags, template, parameters, cfnClient)
  }

  def description = s"Create change set ${stackLookup.changeSetName} for stack ${stackLookup.strategy} with ${templatePath.fileName}"
  def verbose = description
}

class CheckChangeSetCreatedTask(
                                 region: Region,
                                 stackLookup: CloudFormationStackMetadata,
                                 override val duration: Long
)(implicit val keyRing: KeyRing, artifactClient: AmazonS3) extends Task with RepeatedPollingCheck {

  override def execute(reporter: DeployReporter, stopFlag: => Boolean): Unit = {
    check(reporter, stopFlag) {
      val cfnClient = CloudFormation.makeCfnClient(keyRing, region)

      val (stackName, _) = stackLookup.lookup(reporter, cfnClient)
      val changeSetName = stackLookup.changeSetName

      val request = new DescribeChangeSetRequest().withChangeSetName(changeSetName).withStackName(stackName)
      val response = cfnClient.describeChangeSet(request)

      shouldStopWaiting(response.getStatus, response.getStatusReason, response.getChanges.asScala, reporter)
    }
  }

  def shouldStopWaiting(status: String, statusReason: String, changes: Iterable[Change], reporter: DeployReporter): Boolean = {
    import ChangeSetStatus._

    Try(valueOf(status)) match {
      case Success(CREATE_COMPLETE) => true
      case Success(FAILED) if changes.isEmpty => true
      case Success(FAILED) => reporter.fail(statusReason)
      case Success(CREATE_IN_PROGRESS | CREATE_PENDING) =>
        reporter.verbose(status)
        false
      case _ =>
        reporter.fail(s"Unexpected change set status $status")
    }
  }

  def description = s"Checking change set ${stackLookup.changeSetName} creation for stack ${stackLookup.strategy}"
  def verbose = description
}

class ExecuteChangeSetTask(
                            region: Region,
                            stackLookup: CloudFormationStackMetadata,
)(implicit val keyRing: KeyRing, artifactClient: AmazonS3) extends Task {
  override def execute(reporter: DeployReporter, stopFlag: => Boolean): Unit = {
    val cfnClient = CloudFormation.makeCfnClient(keyRing, region)

    val (stackName, _) = stackLookup.lookup(reporter, cfnClient)
    val changeSetName = stackLookup.changeSetName

    val describeRequest = new DescribeChangeSetRequest().withChangeSetName(changeSetName).withStackName(stackName)
    val describeResponse = cfnClient.describeChangeSet(describeRequest)

    if(describeResponse.getChanges.isEmpty) {
      reporter.info(s"No changes to perform for $changeSetName on stack $stackName")
    } else {
      describeResponse.getChanges.asScala.foreach { change =>
        reporter.verbose(s"${change.getType} - ${change.getResourceChange}")
      }

      val request = new ExecuteChangeSetRequest().withChangeSetName(changeSetName).withStackName(stackName)
      cfnClient.executeChangeSet(request)
    }
  }

  def description = s"Execute change set ${stackLookup.changeSetName} on stack ${stackLookup.strategy}"
  def verbose = description
}

class DeleteChangeSetTask(
                           region: Region,
                           stackLookup: CloudFormationStackMetadata,
)(implicit val keyRing: KeyRing, artifactClient: AmazonS3) extends Task {
  override def execute(reporter: DeployReporter, stopFlag: => Boolean): Unit = {
    val cfnClient = CloudFormation.makeCfnClient(keyRing, region)

    val (stackName, _) = stackLookup.lookup(reporter, cfnClient)
    val changeSetName = stackLookup.changeSetName

    val request = new DeleteChangeSetRequest().withChangeSetName(changeSetName).withStackName(stackName)
    cfnClient.deleteChangeSet(request)
  }

  def description = s"Delete change set ${stackLookup.changeSetName} on stack ${stackLookup.strategy}"
  def verbose = description
}