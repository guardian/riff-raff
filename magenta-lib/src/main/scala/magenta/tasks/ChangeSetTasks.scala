package magenta.tasks

import com.amazonaws.services.cloudformation.model.{ChangeSetType, DescribeChangeSetRequest, ExecuteChangeSetRequest}
import com.amazonaws.services.s3.AmazonS3
import magenta.artifact.S3Path
import magenta.deployment_type.CloudFormationDeploymentTypeParameters.{CfnParam, TagCriteria}
import magenta.tasks.CloudFormation.ParameterValue
import magenta.tasks.UpdateCloudFormationTask._
import magenta.{DeployReporter, KeyRing, Region, Stack, Stage}

import scala.collection.JavaConverters._

case class CreateChangeSetTask(
   region: Region,
   cloudFormationStackLookupStrategy: CloudFormationStackLookupStrategy,
   templatePath: S3Path,
   userParameters: Map[String, String],
   amiParameterMap: Map[CfnParam, TagCriteria],
   latestImage: String => String => Map[String,String] => Option[String],
   stage: Stage,
   stack: Stack,
   createStackIfAbsent:Boolean,
   alwaysUploadToS3:Boolean,
   changeSetName: String
)(implicit val keyRing: KeyRing, artifactClient: AmazonS3) extends Task {

  override def execute(reporter: DeployReporter, stopFlag: => Boolean) = if (!stopFlag) {
    val cfnClient = CloudFormation.makeCfnClient(keyRing, region)
    val s3Client = S3.makeS3client(keyRing, region)
    val stsClient = STS.makeSTSclient(keyRing, region)
    val accountNumber = STS.getAccountNumber(stsClient)

    val maybeCfStack = cloudFormationStackLookupStrategy match {
      case LookupByName(cloudFormationStackName) => CloudFormation.describeStack(cloudFormationStackName, cfnClient)
      case LookupByTags(tags) => CloudFormation.findStackByTags(tags, reporter, cfnClient)
    }

    val templateString = templatePath.fetchContentAsString.right.getOrElse(
      reporter.fail(s"Unable to locate cloudformation template s3://${templatePath.bucket}/${templatePath.key}")
    )

    val stackName = UpdateCloudFormationTask.nameToCallNewStack(cloudFormationStackLookupStrategy)
    val template = processTemplate(stackName, templateString, s3Client, stsClient, region, alwaysUploadToS3, reporter)

    val templateParameters = CloudFormation.validateTemplate(template, cfnClient).getParameters.asScala
      .map(tp => TemplateParameter(tp.getParameterKey, Option(tp.getDefaultValue).isDefined))

    // TODO MRB: should this be the "SAM" deployment type and not have ami parameters et al
    val resolvedAmiParameters: Map[String, String] = amiParameterMap.flatMap { case (name, tags) =>
      val ami = latestImage(accountNumber)(region.name)(tags)
      ami.map(name ->)
    }

    val parameters: Map[String, ParameterValue] =
      combineParameters(stack, stage, templateParameters, userParameters ++ resolvedAmiParameters)

    reporter.info("Creating Cloudformation change set")
    reporter.info(s"Stack name: $stackName")
    reporter.info(s"Change set name: $changeSetName")
    reporter.info(s"Parameters: $parameters")

    val stackTags = PartialFunction.condOpt(cloudFormationStackLookupStrategy){ case LookupByTags(tags) => tags }
    val changeSetType = if(maybeCfStack.isEmpty) { ChangeSetType.CREATE } else { ChangeSetType.UPDATE }

    if(changeSetType == ChangeSetType.CREATE && createStackIfAbsent) {
      CloudFormation.createChangeSet(reporter, changeSetName, changeSetType, stackName, stackTags, template, parameters, cfnClient)
    } else {
      reporter.fail(s"Stack $cloudFormationStackLookupStrategy doesn't exist and createStackIfAbsent is false")
    }
  }

  def description = s"Create change set $changeSetName for stack $cloudFormationStackLookupStrategy with ${templatePath.fileName}"
  def verbose = description
}

case class CheckChangeSetCreatedTask(
  region: Region,
  cloudFormationStackLookupStrategy: CloudFormationStackLookupStrategy,
  changeSetName: String,
  duration: Long
)(implicit val keyRing: KeyRing, artifactClient: AmazonS3) extends Task with RepeatedPollingCheck {

  override def execute(reporter: DeployReporter, stopFlag: => Boolean): Unit = {
    check(reporter, stopFlag) {
      val cfnClient = CloudFormation.makeCfnClient(keyRing, region)
      val stackName = UpdateCloudFormationTask.nameToCallNewStack(cloudFormationStackLookupStrategy)

      val request = new DescribeChangeSetRequest().withChangeSetName(changeSetName).withStackName(stackName)
      val response = cfnClient.describeChangeSet(request)

      response.getStatus match {
        case "CREATE_COMPLETE" =>
          true

        case "FAILED" =>
          reporter.fail(response.getStatusReason)

        case other =>
          reporter.verbose(other)
          false
      }
    }
  }

  def description = s"Checking change set $changeSetName creation for stack $cloudFormationStackLookupStrategy"
  def verbose = description
}

case class ExecuteChangeSetTask(
  region: Region,
  cloudFormationStackLookupStrategy: CloudFormationStackLookupStrategy,
  changeSetName: String
)(implicit val keyRing: KeyRing, artifactClient: AmazonS3) extends Task {
  override def execute(reporter: DeployReporter, stopFlag: => Boolean): Unit = {
    val cfnClient = CloudFormation.makeCfnClient(keyRing, region)
    val stackName = UpdateCloudFormationTask.nameToCallNewStack(cloudFormationStackLookupStrategy)

    val request = new ExecuteChangeSetRequest().withChangeSetName(changeSetName).withStackName(stackName)
    cfnClient.executeChangeSet(request)
  }

  def description = s"Execute change set $changeSetName on stack $cloudFormationStackLookupStrategy"
  def verbose = description
}
