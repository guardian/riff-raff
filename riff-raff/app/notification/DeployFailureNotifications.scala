package notification

import java.util.UUID
import ci.{ContinuousDeployment, TargetResolver}
import com.gu.anghammarad.Anghammarad
import com.gu.anghammarad.models._
import conf.Config
import controllers.Logging
import deployment.ScheduledDeployNotificationError
import lifecycle.Lifecycle
import magenta.Message.Fail
import magenta.input.RiffRaffYamlReader
import magenta.input.resolver.{DeploymentResolver}
import magenta.tasks.STS
import magenta.{
  DeployParameters,
  DeployReporter,
  Lookup,
  Region,
  StsDeploymentResources,
  App => MagentaApp,
  Stack => MagentaStack
}
import schedule.ScheduledDeployer
import rx.lang.scala.Subscription

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

class DeployFailureNotifications(
    config: Config,
    targetResolver: TargetResolver,
    lookup: Lookup
)(implicit ec: ExecutionContext)
    extends Lifecycle
    with Logging {
  lazy private val prefix = config.urls.publicPrefix
  lazy private val fallbackTargets = List(Stack("deploy"))
  lazy private val failureNotificationContents =
    new FailureNotificationContents(prefix)

  def getAwsAccountIdTarget(
      target: ci.Target,
      parameters: DeployParameters,
      uuid: UUID
  ): Option[Target] = {
    Try {
      val keyring = lookup.keyRing(
        parameters.stage,
        MagentaApp(target.app),
        MagentaStack(target.stack)
      )
      STS.withSTSclient(
        keyring,
        Region(target.region),
        StsDeploymentResources(uuid, config.credentials.stsClient)
      ) { client =>
        AwsAccount(STS.getAccountNumber(client))
      }
    } match {
      case Success(value) =>
        Some(value)
      case Failure(exception) =>
        log.error("Failed to fetch AWS account ID", exception)
        None
    }
  }

  def getTargets(uuid: UUID, parameters: DeployParameters): List[Target] = {
    (for {
      yaml <- targetResolver.fetchYaml(parameters.build)
      deployConfig <- RiffRaffYamlReader.fromString(yaml).toEither
      partiallyResolvedDeployment <- DeploymentResolver
        .resolve(deployConfig)
        .toEither
    } yield {
      TargetResolver
        .extractTargets(partiallyResolvedDeployment)
        .toList
        .flatMap { target =>
          val maybeAccountId =
            getAwsAccountIdTarget(target, parameters, uuid).toList
          List(
            App(target.app),
            Stack(target.stack),
            Stage(parameters.stage.name)
          ) ++ maybeAccountId
        }
    }) match {
      case Right(_) if multiAccountDevXProject(parameters.build.projectName) =>
        val targetOverride = GithubTeamSlug("devx-reliability-and-ops")
        log.info(
          s"Default notification targets for ${parameters.build.projectName} were overridden with $targetOverride"
        )
        List(targetOverride)
      // This should be the typical case
      case Right(targets) => targets
      case Left(error)    =>
        log.warn(
          s"Failed to identify notification targets for ${parameters.build.projectName} due to $error"
        )
        fallbackTargets
    }
  }

  def notifyViaAnghammarad(
      notificationContents: NotificationContents,
      targets: List[Target]
  ) = {
    log.info(s"Sending anghammarad notification with targets: ${targets.toSet}")
    Anghammarad
      .notify(
        subject = notificationContents.subject,
        message = notificationContents.message,
        sourceSystem = "riff-raff",
        channel = All,
        target = targets,
        actions = notificationContents.actions,
        topicArn = config.anghammarad.topicArn,
        client = config.anghammarad.snsClient
      )
      .recover { case ex =>
        log.error(s"Failed to send notification (via Anghammarad)", ex)
      }
  }

  def scheduledDeployFailureNotification(
      error: ScheduledDeployNotificationError
  ): Unit = {
    val contentsWithTargets =
      failureNotificationContents.scheduledDeployFailureNotificationContents(
        error,
        getTargets,
        fallbackTargets
      )
    notifyViaAnghammarad(
      contentsWithTargets.notificationContents,
      contentsWithTargets.targets
    )
  }

  def midDeployFailureNotification(
      uuid: UUID,
      parameters: DeployParameters,
      targets: List[Target]
  ): Unit = {
    val notificationContents = failureNotificationContents
      .midDeployFailureNotificationContents(uuid, parameters)
    notifyViaAnghammarad(notificationContents, targets)
  }

  def scheduledDeploy(deployParameters: DeployParameters): Boolean =
    deployParameters.deployer == ScheduledDeployer.deployer
  def continuousDeploy(deployParameters: DeployParameters): Boolean =
    deployParameters.deployer == ContinuousDeployment.deployer

  val messageSub: Subscription = DeployReporter.messages.subscribe(message => {
    message.stack.top match {
      case Fail(_, _)
          if scheduledDeploy(message.context.parameters) || continuousDeploy(
            message.context.parameters
          ) =>
        log.info(s"Attempting to send notification via Anghammarad")
        val targets =
          getTargets(message.context.deployId, message.context.parameters)
        midDeployFailureNotification(
          message.context.deployId,
          message.context.parameters,
          targets
        )
      case _ =>
    }
  })

  private def multiAccountDevXProject(projectName: String): Boolean = {

    /* In the context of these projects, we are deploying to multiple AWS accounts / stacks.
     By default, Riff-Raff will send Anghammarad a huge list of targets and the notifications are often sent
     to surprising Chat channels/email addresses!

     Consequently, in these special cases where we know that DevX should receive the notification, we override
     the default behaviour.
     */
    val platformProjects = List(
      "devx::aws-account-setup", // https://github.com/guardian/aws-account-setup
      "elasticsearch-node-rotation", // https://github.com/guardian/elasticsearch-node-rotation
      "guardian-dns-record-set-type", // https://github.com/guardian/cfn-private-resource-types
      "tools::waf", // https://github.com/guardian/waf
      "devx::slo-alerts" // https://github.com/guardian/slo-alerts
    )
    platformProjects.contains(projectName)

  }

  def init(): Unit = {}

  def shutdown(): Unit = {
    messageSub.unsubscribe()
  }
}
