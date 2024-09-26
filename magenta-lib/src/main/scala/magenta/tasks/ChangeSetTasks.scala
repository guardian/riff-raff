package magenta.tasks

import magenta.artifact.S3Path
import magenta.tasks.CloudFormationParameters.{
  ExistingParameter,
  InputParameter,
  TemplateParameter
}
import magenta.tasks.UpdateCloudFormationTask._
import magenta.{DeployReporter, DeploymentResources, KeyRing, Region}
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.ChangeSetStatus._
import software.amazon.awssdk.services.cloudformation.model._
import software.amazon.awssdk.services.s3.S3Client

import java.time.Duration
import scala.jdk.CollectionConverters._
import scala.util.{Success, Try}

trait CloudFormationParameterLogger {
  def convertInputParametersToAwsAndLog(
      reporter: DeployReporter,
      parameters: List[InputParameter],
      existingParameters: List[ExistingParameter]
  ): List[Parameter] = {
    val awsParameters: List[Parameter] =
      CloudFormationParameters.convertInputParametersToAws(parameters)

    val parametersString = awsParameters
      .map { param =>
        val v =
          if (param.usePreviousValue) "PreviousValue"
          else s"""Value: "${param.parameterValue}""""
        s"${param.parameterKey} -> $v"
      }
      .mkString("; ")

    reporter.info(s"Parameters: $parametersString")

    changedParamValues(existingParameters, parameters).foreach { change =>
      reporter.info(change)
    }

    awsParameters
  }

  def changedParamValues(
      existingParams: List[ExistingParameter],
      newParams: List[InputParameter]
  ): List[String] = {
    val keys =
      (existingParams.map(_.key) ::: newParams.map(_.key)).sorted.distinct
    val pairs = keys.map { key =>
      (key, existingParams.find(_.key == key), newParams.find(_.key == key))
    }
    pairs.flatMap {
      case (key, Some(_), None) => Some(s"Parameter $key has been removed")
      case (key, None, Some(_)) => Some(s"Parameter $key has been added")
      case (
            key,
            Some(ExistingParameter(_, present, _)),
            Some(InputParameter(_, Some(future), false))
          ) if future != present && present != "****" =>
        Some(s"Parameter $key has changed from $present to $future")
      case _ => None
    }
  }
}

class CreateAmiUpdateChangeSetTask(
    region: Region,
    stackLookup: CloudFormationStackMetadata,
    val unresolvedParameters: CloudFormationParameters
)(implicit val keyRing: KeyRing)
    extends Task
    with CloudFormationParameterLogger {

  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    if (!stopFlag) {
      CloudFormation.withCfnClient(keyRing, region, resources) { cfnClient =>
        STS.withSTSclient(keyRing, region, resources) { stsClient =>
          val accountNumber = STS.getAccountNumber(stsClient)

          val (stackName, _, existingParameters, currentTags) =
            stackLookup.lookup(resources.reporter, cfnClient)

          val maybeExecutionRole = CloudFormation.getExecutionRole(keyRing)
          maybeExecutionRole.foreach(role =>
            resources.reporter.verbose(s"Using execution role $role")
          )

          val parameters = CloudFormationParameters
            .resolve(
              resources.reporter,
              unresolvedParameters,
              accountNumber,
              existingParameters.map(p => TemplateParameter(p.key, true)),
              existingParameters
            )
            .fold(
              resources.reporter.fail(_),
              identity
            )

          val awsParameters = convertInputParametersToAwsAndLog(
            resources.reporter,
            parameters,
            existingParameters
          )

          CloudFormation.createParameterUpdateChangeSet(
            client = cfnClient,
            changeSetName = stackLookup.changeSetName,
            stackName = stackName,
            currentStackTags = currentTags,
            parameters = awsParameters,
            maybeRole = maybeExecutionRole
          )
        }
      }
    }
  }

  override def description: String =
    s"Create change set ${stackLookup.changeSetName} for stack ${stackLookup.strategy} to update AMI parameters "
}

class CreateChangeSetTask(
    region: Region,
    templatePath: S3Path,
    stackLookup: CloudFormationStackMetadata,
    val unresolvedParameters: CloudFormationParameters,
    val stackTags: Map[String, String]
)(implicit val keyRing: KeyRing, artifactClient: S3Client)
    extends Task
    with CloudFormationParameterLogger {

  override def execute(resources: DeploymentResources, stopFlag: => Boolean) =
    if (!stopFlag) {
      CloudFormation.withCfnClient(keyRing, region, resources) { cfnClient =>
        S3.withS3client(keyRing, region, resources = resources) { s3Client =>
          STS.withSTSclient(keyRing, region, resources) { stsClient =>
            val accountNumber = STS.getAccountNumber(stsClient)

            val templateString = templatePath
              .fetchContentAsString()
              .right
              .getOrElse(
                resources.reporter.fail(
                  s"Unable to locate cloudformation template s3://${templatePath.bucket}/${templatePath.key}"
                )
              )

            val (stackName, changeSetType, existingParameters, currentTags) =
              stackLookup.lookup(resources.reporter, cfnClient)

            val template = processTemplate(
              stackName,
              templateString,
              s3Client,
              stsClient,
              region,
              resources.reporter
            )
            val templateParameters = CloudFormation
              .validateTemplate(template, cfnClient)
              .parameters
              .asScala
              .toList
              .map(tp =>
                TemplateParameter(
                  tp.parameterKey,
                  Option(tp.defaultValue).isDefined
                )
              )

            val parameters = CloudFormationParameters
              .resolve(
                resources.reporter,
                unresolvedParameters,
                accountNumber,
                templateParameters,
                existingParameters
              )
              .fold(
                resources.reporter.fail(_),
                identity
              )

            val awsParameters =
              convertInputParametersToAwsAndLog(
                resources.reporter,
                parameters,
                existingParameters
              )

            resources.reporter.info("Creating Cloudformation change set")
            resources.reporter.info(s"Stack name: $stackName")
            resources.reporter.info(
              s"Change set name: ${stackLookup.changeSetName}"
            )

            val maybeExecutionRole = CloudFormation.getExecutionRole(keyRing)
            maybeExecutionRole.foreach(role =>
              resources.reporter.verbose(s"Using execution role $role")
            )

            val mergedTags = currentTags ++ stackTags
            resources.reporter.info("Tags: " + mergedTags.mkString(", "))

            CloudFormation.createChangeSet(
              resources.reporter,
              stackLookup.changeSetName,
              changeSetType,
              stackName,
              Some(mergedTags),
              template,
              awsParameters,
              maybeExecutionRole,
              cfnClient
            )
          }
        }
      }
    }

  def description =
    s"Create change set ${stackLookup.changeSetName} for stack ${stackLookup.strategy} with ${templatePath.fileName}"
}

class CheckChangeSetCreatedTask(
    region: Region,
    stackLookup: CloudFormationStackMetadata,
    override val duration: Duration
)(implicit val keyRing: KeyRing)
    extends Task
    with RepeatedPollingCheck {

  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    check(resources.reporter, stopFlag) {
      CloudFormation.withCfnClient(keyRing, region, resources) { cfnClient =>
        val (stackName, changeSetType, _, _) =
          stackLookup.lookup(resources.reporter, cfnClient)
        val changeSetName = stackLookup.changeSetName
        val changeSet = CloudFormation.describeChangeSetByName(
          stackName,
          changeSetName,
          cfnClient
        )

        shouldStopWaiting(
          changeSetType,
          changeSet.status.toString,
          changeSet.statusReason,
          changeSet.changes.asScala,
          resources.reporter
        )
      }
    }
  }

  def isNoOpStatusReason(status: String): Boolean = {
    status == "The submitted information didn't contain changes. Submit different information to create a change set." ||
    status == "No updates are to be performed."
  }

  def shouldStopWaiting(
      changeSetType: ChangeSetType,
      status: String,
      statusReason: String,
      changes: Iterable[Change],
      reporter: DeployReporter
  ): Boolean = {
    Try(valueOf(status)) match {
      case Success(CREATE_COMPLETE) => true
      // special case an empty change list when the status reason is no updates
      case Success(FAILED)
          if changes.isEmpty && isNoOpStatusReason(statusReason) =>
        reporter.info(
          s"Couldn't create change set as the stack is already up to date"
        )
        true
      case Success(FAILED) => reporter.fail(statusReason)
      case Success(CREATE_IN_PROGRESS | CREATE_PENDING) =>
        reporter.verbose(status)
        false
      case _ =>
        reporter.fail(s"Unexpected change set status $status")
    }
  }

  def description =
    s"Checking change set ${stackLookup.changeSetName} creation for stack ${stackLookup.strategy}"
}

/** A task to execute a ChangeSet against a CloudFormation stack.
  *
  * If the ChangeSet does not contain any changes, it exits early.
  *
  * If there are changes, it waits until:
  *   - The CloudFormation event log contains `UPDATE_COMPLETE` or
  *     `CREATE_COMPLETE`
  *   - The execution status of the ChangeSet is `EXECUTE_COMPLETE`
  *
  * @see
  *   [[CloudFormationStackEventPoller]]
  * @see
  *   https://docs.aws.amazon.com/AWSCloudFormation/latest/APIReference/API_DescribeChangeSet.html#API_DescribeChangeSet_ResponseElements
  */
class ExecuteChangeSetTask(
    region: Region,
    stackLookup: CloudFormationStackMetadata
)(implicit val keyRing: KeyRing)
    extends Task {
  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    CloudFormation.withCfnClient(keyRing, region, resources) { cfnClient =>
      val (stackName, _, _, _) =
        stackLookup.lookup(resources.reporter, cfnClient)
      val changeSetName = stackLookup.changeSetName

      val changeSet = CloudFormation.describeChangeSetByName(
        stackName,
        changeSetName,
        cfnClient
      )

      val changeSetArn = changeSet.changeSetId()

      if (changeSet.changes.isEmpty) {
        resources.reporter.info(
          s"No changes to perform for $changeSetName on stack $stackName"
        )
      } else {
        changeSet.changes.asScala.foreach { change =>
          resources.reporter.verbose(
            s"${change.`type`} - ${change.resourceChange}"
          )
        }

        CloudFormation.executeChangeSet(stackName, changeSetName, cfnClient)

        new CloudFormationStackEventPoller(
          stackName,
          cfnClient,
          resources,
          stopFlag
        )
          .check(
            None,
            () => {
              val executionStatus = CloudFormation
                .describeChangeSetByArn(changeSetArn, cfnClient)
                .executionStatusAsString()

              // Any other status is a failure condition, which `CloudFormationStackEventPoller` is handling at the CloudFormation event log level
              val isComplete = executionStatus == "EXECUTE_COMPLETE"

              if (!isComplete) {
                resources.reporter.verbose(
                  s"Execution status for change set $changeSetName on stack $stackName is $executionStatus"
                )
              }
              isComplete
            }
          )
      }
    }
  }

  def description =
    s"Execute change set ${stackLookup.changeSetName} on stack ${stackLookup.strategy}"
}

class DeleteChangeSetTask(
    region: Region,
    stackLookup: CloudFormationStackMetadata
)(implicit val keyRing: KeyRing)
    extends Task {
  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    CloudFormation.withCfnClient(keyRing, region, resources) { cfnClient =>
      val (stackName, _, _, _) =
        stackLookup.lookup(resources.reporter, cfnClient)
      val changeSetName = stackLookup.changeSetName

      CloudFormation.deleteChangeSet(stackName, changeSetName, cfnClient)
    }
  }

  def description =
    s"Delete change set ${stackLookup.changeSetName} on stack ${stackLookup.strategy}"
}
