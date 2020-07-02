package magenta.tasks

import magenta.artifact.S3Path
import magenta.tasks.CloudFormationParameters.{ExistingParameter, InputParameter, TemplateParameter}
import magenta.tasks.UpdateCloudFormationTask._
import magenta.{DeployReporter, DeploymentResources, KeyRing, Region}
import software.amazon.awssdk.services.cloudformation.model.ChangeSetStatus._
import software.amazon.awssdk.services.cloudformation.model.{Change, ChangeSetType, DeleteChangeSetRequest, DescribeChangeSetRequest, ExecuteChangeSetRequest}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.sts.StsClient

import scala.collection.JavaConverters._
import scala.util.{Success, Try}

class CreateChangeSetTask(
                           region: Region,
                           templatePath: S3Path,
                           stackLookup: CloudFormationStackMetadata,
                           val unresolvedParameters: CloudFormationParameters
)(implicit val keyRing: KeyRing, artifactClient: S3Client) extends Task {

  override def execute(resources: DeploymentResources, stopFlag: => Boolean ) = if (!stopFlag) {
    CloudFormation.withCfnClient(keyRing, region, resources) { cfnClient =>
      S3.withS3client(keyRing, region, resources = resources) { s3Client =>
        STS.withSTSclient(keyRing, region, resources) { stsClient =>
          val accountNumber = STS.getAccountNumber(stsClient)

          val templateString = templatePath.fetchContentAsString.right.getOrElse(
            resources.reporter.fail(s"Unable to locate cloudformation template s3://${templatePath.bucket}/${templatePath.key}")
          )

          val (stackName, changeSetType, existingParameters) = stackLookup.lookup(resources.reporter, cfnClient)

          val template = processTemplate(stackName, templateString, s3Client, stsClient, region, resources.reporter)
          val templateParameters = CloudFormation.validateTemplate(template, cfnClient).parameters.asScala.toList
            .map(tp => TemplateParameter(tp.parameterKey, Option(tp.defaultValue).isDefined))

          val parameters = CloudFormationParameters.resolve(unresolvedParameters, accountNumber, templateParameters, existingParameters).fold(
            resources.reporter.fail(_),
            identity
          )
          val awsParameters = CloudFormationParameters.convertInputParametersToAws(parameters)

          resources.reporter.info("Creating Cloudformation change set")
          resources.reporter.info(s"Stack name: $stackName")
          resources.reporter.info(s"Change set name: ${stackLookup.changeSetName}")

          val parametersString = awsParameters.map { param =>
            val v = if (param.usePreviousValue) "PreviousValue" else s"""Value: "${param.parameterValue}""""
            s"${param.parameterKey} -> $v"
          }.mkString("; ")
          resources.reporter.info(s"Parameters: $parametersString")

          changedParamValues(existingParameters, parameters).foreach { change =>
            resources.reporter.info(change)
          }

          CloudFormation.createChangeSet(stackLookup.changeSetName, changeSetType, stackName, unresolvedParameters.stackTags, template, awsParameters, cfnClient)
        }
      }
    }
  }

  def changedParamValues(existingParams: List[ExistingParameter], newParams: List[InputParameter]): List[String] = {
    val keys = (existingParams.map(_.key) ::: newParams.map(_.key)).sorted.distinct
    val pairs = keys.map { key =>
      (key, existingParams.find(_.key == key), newParams.find(_.key == key))
    }
    pairs.flatMap {
      case (key, Some(_), None) => Some(s"Parameter $key has been removed")
      case (key, None, Some(_)) => Some(s"Parameter $key has been added")
      case (key, Some(ExistingParameter(_, present, _)), Some(InputParameter(_, Some(future), false)))
        if future != present && present != "****" =>
          Some(s"Parameter $key has changed from $present to $future")
      case _ => None
    }
  }

  def description = s"Create change set ${stackLookup.changeSetName} for stack ${stackLookup.strategy} with ${templatePath.fileName}"
}

class CheckChangeSetCreatedTask(
                                 region: Region,
                                 stackLookup: CloudFormationStackMetadata,
                                 override val duration: Long
)(implicit val keyRing: KeyRing, artifactClient: S3Client) extends Task with RepeatedPollingCheck {

  override def execute(resources: DeploymentResources, stopFlag: => Boolean): Unit = {
    check(resources.reporter, stopFlag) {
      CloudFormation.withCfnClient(keyRing, region, resources) { cfnClient =>
        val (stackName, changeSetType, _) = stackLookup.lookup(resources.reporter, cfnClient)
        val changeSetName = stackLookup.changeSetName

        val request = DescribeChangeSetRequest.builder().changeSetName(changeSetName).stackName(stackName).build()
        val response = cfnClient.describeChangeSet(request)

        shouldStopWaiting(changeSetType, response.status.toString, response.statusReason, response.changes.asScala, resources.reporter)
      }
    }
  }

  def isNoOpStatusReason(status: String): Boolean = {
    status == "The submitted information didn't contain changes. Submit different information to create a change set." ||
    status == "No updates are to be performed."
  }

  def shouldStopWaiting(changeSetType: ChangeSetType, status: String, statusReason: String, changes: Iterable[Change], reporter: DeployReporter): Boolean = {
    Try(valueOf(status)) match {
      case Success(CREATE_COMPLETE) => true
      // special case an empty change list when the status reason is no updates
      case Success(FAILED) if changes.isEmpty && isNoOpStatusReason(statusReason) =>
        reporter.info(s"Couldn't create change set as the stack is already up to date")
        true
      case Success(FAILED) => reporter.fail(statusReason)
      case Success(CREATE_IN_PROGRESS | CREATE_PENDING) =>
        reporter.verbose(status)
        false
      case _ =>
        reporter.fail(s"Unexpected change set status $status")
    }
  }

  def description = s"Checking change set ${stackLookup.changeSetName} creation for stack ${stackLookup.strategy}"
}

class ExecuteChangeSetTask(
                            region: Region,
                            stackLookup: CloudFormationStackMetadata,
)(implicit val keyRing: KeyRing, artifactClient: S3Client) extends Task {
  override def execute(resources: DeploymentResources, stopFlag: => Boolean): Unit = {
    CloudFormation.withCfnClient(keyRing, region, resources) { cfnClient =>
      val (stackName, _, _) = stackLookup.lookup(resources.reporter, cfnClient)
      val changeSetName = stackLookup.changeSetName

      val describeRequest = DescribeChangeSetRequest.builder().changeSetName(changeSetName).stackName(stackName).build()
      val describeResponse = cfnClient.describeChangeSet(describeRequest)

      if (describeResponse.changes.isEmpty) {
        resources.reporter.info(s"No changes to perform for $changeSetName on stack $stackName")
      } else {
        describeResponse.changes.asScala.foreach { change =>
          resources.reporter.verbose(s"${change.`type`} - ${change.resourceChange}")
        }

        val request = ExecuteChangeSetRequest.builder().changeSetName(changeSetName).stackName(stackName).build()
        cfnClient.executeChangeSet(request)
      }
    }
  }

  def description = s"Execute change set ${stackLookup.changeSetName} on stack ${stackLookup.strategy}"
}

class DeleteChangeSetTask(
                           region: Region,
                           stackLookup: CloudFormationStackMetadata,
)(implicit val keyRing: KeyRing, artifactClient: S3Client) extends Task {
  override def execute(resources: DeploymentResources, stopFlag: => Boolean): Unit = {
    CloudFormation.withCfnClient(keyRing, region, resources) { cfnClient =>
      val (stackName, _, _) = stackLookup.lookup(resources.reporter, cfnClient)
      val changeSetName = stackLookup.changeSetName

      val request = DeleteChangeSetRequest.builder().changeSetName(changeSetName).stackName(stackName).build()
      cfnClient.deleteChangeSet(request)
    }
  }

  def description = s"Delete change set ${stackLookup.changeSetName} on stack ${stackLookup.strategy}"
}