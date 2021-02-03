package notification

import java.util.UUID
import ci.{ContinuousDeployment, TargetResolver}
import com.gu.anghammarad.Anghammarad
import com.gu.anghammarad.models._
import conf.Config
import controllers.{Logging, routes}
import deployment.Error
import deployment.{NoDeploysFoundForStage, ScheduledDeployError, SkippedDueToPreviousFailure, SkippedDueToPreviousWaitingDeploy}
import lifecycle.Lifecycle
import magenta.Message.Fail
import magenta.deployment_type.DeploymentType
import magenta.input.resolver.Resolver
import magenta.tasks.STS
import magenta.{DeployParameters, DeployReporter, Lookup, Region, StsDeploymentResources, App => MagentaApp, Stack => MagentaStack}
import schedule.ScheduledDeployer
import rx.lang.scala.Subscription

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

class DeployFailureNotifications(config: Config,
                                 deploymentTypes: Seq[DeploymentType],
                                 targetResolver: TargetResolver,
  lookup: Lookup)
                                (implicit ec: ExecutionContext)
  extends Lifecycle with Logging {

  lazy private val anghammaradTopicARN = config.scheduledDeployment.anghammaradTopicARN
  lazy private val snsClient = config.scheduledDeployment.snsClient
  lazy private val prefix = config.urls.publicPrefix

  def url(uuid: UUID): String = {
    val path = routes.DeployController.viewUUID(uuid.toString, verbose = true)
    prefix + path.url
  }

  def getAwsAccountIdTarget(target: ci.Target, parameters: DeployParameters, uuid: UUID): Option[Target] = {
    Try {
      val keyring = lookup.keyRing(parameters.stage, MagentaApp(target.app), MagentaStack(target.stack))
      STS.withSTSclient(keyring, Region(target.region), StsDeploymentResources(uuid, config.credentials.stsClient)){ client =>
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

  case class MessageWithActions(message: String, actions: List[Action])

  def scheduledDeployNotificationDetails(scheduledDeployError: ScheduledDeployError, errorMessage: String): MessageWithActions = {
    val deployManually = Action("Deploy project manually", "https://riffraff.gutools.co.uk/deployment/request")
    scheduledDeployError match {
      case SkippedDueToPreviousFailure =>
        val message = s"Your scheduled deploy didn't start because the most recent deploy was in a bad state: $errorMessage"
        MessageWithActions(message, List(deployManually))
      case SkippedDueToPreviousWaitingDeploy =>
        val message = s"A scheduled deploy failed to start as a previous deploy was stuck: $errorMessage"
        MessageWithActions(message, List())
      case NoDeploysFoundForStage =>
        val message = s"Your scheduled deploy didn't start because RiffRaff has never deployed your project to the specified stage before: $errorMessage"
        val scheduledDeployConfig = Action("Check scheduled deploy configuration", "https://riffraff.gutools.co.uk/deployment/schedule")
        MessageWithActions(message, List(deployManually, scheduledDeployConfig))
    }
  }

  def failedDeployNotification(uuid: Option[UUID], maybeParameters: Option[DeployParameters], error: Option[Error] = None): Unit = {

    val deriveAnghammaradTargets = maybeParameters match {
      case Some(parameters) => for {
        yaml <- targetResolver.fetchYaml(parameters.build)
        deployGraph <- Resolver.resolveDeploymentGraph(yaml, deploymentTypes, magenta.input.All).toEither
      } yield {
        TargetResolver.extractTargets(deployGraph).toList.flatMap { target =>
          val maybeAccountId = uuid.flatMap(uuid => getAwsAccountIdTarget(target, parameters, uuid)).toList
          List(App(target.app), Stack(target.stack), Stage(parameters.stage.name)) ++ maybeAccountId
        }
      }
      case None => Right(List(App("riff-raff"), Stack("deploy")))
    }

    val scheduledDeployFailedToStart = error.flatMap(_.scheduledDeployError).isDefined
    val (subject, notificationMessage) = maybeParameters match {
      case Some(parameters) =>
        val subject = if (scheduledDeployFailedToStart) s"${parameters.deployer.name} failed to start" else s"${parameters.deployer.name} failed"
        val message = s"${parameters.deployer.name} for ${parameters.build.projectName} (build ${parameters.build.id}) to stage ${parameters.stage.name} failed."
        (subject, message)
      case None =>
        val subject = "Scheduled deployment failed to start"
        val message = "Your scheduled deploy didn't start because RiffRaff has never deployed this project to the specified stage before."
        (subject, message)
    }

    val defaultNotificationActions = uuid.map(id => List(Action("View failed deploy", url(id)))).getOrElse(Nil)

    val scheduledDeployFailedMessageWithActions = for {
      error <- error
      scheduledDeployError <- error.scheduledDeployError
    } yield {
      scheduledDeployNotificationDetails(scheduledDeployError, error.message)
    }

    val messageWithActions = scheduledDeployFailedMessageWithActions.getOrElse(
      MessageWithActions(notificationMessage, defaultNotificationActions)
    )

    deriveAnghammaradTargets match {
      case Right(targets) =>
        log.info(s"Sending anghammarad notification with targets: ${targets.toSet}")
        Anghammarad.notify(
          subject = subject,
          message = messageWithActions.message,
          sourceSystem = "riff-raff",
          channel = All,
          target = targets, // TODO: Make this dynamically set to the most appropriate target based on reason for failure
          actions = messageWithActions.actions,
          topicArn = anghammaradTopicARN,
          client = snsClient
        ).recover { case ex => log.error(s"Failed to send notification (via Anghammarad) for $uuid", ex) }
      case Left(_) =>
        log.error(s"Failed to derive targets required to notify about failed scheduled deploy: $uuid")
    }
  }

  def scheduledDeploy(deployParameters: DeployParameters): Boolean = deployParameters.deployer == ScheduledDeployer.deployer
  def continuousDeploy(deployParameters: DeployParameters): Boolean = deployParameters.deployer == ContinuousDeployment.deployer

  val messageSub: Subscription = DeployReporter.messages.subscribe(message => {
    message.stack.top match {
      case Fail(_, _) if scheduledDeploy(message.context.parameters) || continuousDeploy(message.context.parameters) =>
        log.info(s"Attempting to send notification via Anghammarad")
        failedDeployNotification(Some(message.context.deployId), Some(message.context.parameters))
      case _ =>
    }
  })

  def init() {}

  def shutdown() {
    messageSub.unsubscribe()
  }
}
