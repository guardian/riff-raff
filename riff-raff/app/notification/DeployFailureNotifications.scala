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
  lazy private val riffRaffTargets = List(App("riff-raff"), Stack("deploy"))

  case class NotificationContents(subject: String, message: String, actions: List[Action])
  case class NotificationContentsWithTargets(notificationContents: NotificationContents, targets: List[Target])

  def problematicDeployUrl(uuid: UUID): String = {
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

  def getTargets(uuid: Option[UUID], maybeParameters: Option[DeployParameters]): List[Target] = {
    val attemptToDeriveTargets = maybeParameters.map { parameters =>
       for {
        yaml <- targetResolver.fetchYaml(parameters.build)
        deployGraph <- Resolver.resolveDeploymentGraph(yaml, deploymentTypes, magenta.input.All).toEither
      } yield {
        TargetResolver.extractTargets(deployGraph).toList.flatMap { target =>
          val maybeAccountId = uuid.flatMap(uuid => getAwsAccountIdTarget(target, parameters, uuid)).toList
          List(App(target.app), Stack(target.stack), Stage(parameters.stage.name)) ++ maybeAccountId
        }
      }
    }
    attemptToDeriveTargets match {
      case Some(Right(targets)) => targets
      case _ => riffRaffTargets
    }
  }

  def notifyViaAnghammarad(notificationContents: NotificationContents, targets: List[Target]) = {
    log.info(s"Sending anghammarad notification with targets: ${targets.toSet}")
    Anghammarad.notify(
      subject = notificationContents.subject,
      message = notificationContents.message,
      sourceSystem = "riff-raff",
      channel = All,
      target = targets,
      actions = notificationContents.actions,
      topicArn = anghammaradTopicARN,
      client = snsClient
    ).recover { case ex => log.error(s"Failed to send notification (via Anghammarad)", ex) }
  }

  def notificationContentsWithTargets(scheduledDeployError: ScheduledDeployError): NotificationContentsWithTargets = {
    val subject = "Scheduled deployment failed to start"
    def viewProblematicDeploy(uuid: UUID, status: String) = Action(s"View $status deploy", problematicDeployUrl(uuid))
    val deployManually = Action("Deploy project manually", prefix + routes.DeployController.deploy().url)
    scheduledDeployError match {
      case SkippedDueToPreviousFailure(record) =>
        val message = s"Your scheduled deploy didn't start because the most recent deploy failed"
        val contents = NotificationContents(subject, message, List(deployManually, viewProblematicDeploy(record.uuid, "failed")))
        NotificationContentsWithTargets(contents, getTargets(None, Some(record.parameters)))
      case SkippedDueToPreviousWaitingDeploy(record) =>
        val message = s"A scheduled deploy failed to start as a previous deploy was stuck"
        val contents = NotificationContents(subject, message, List(viewProblematicDeploy(record.uuid, "waiting")))
        NotificationContentsWithTargets(contents, riffRaffTargets)
      case NoDeploysFoundForStage(projectName, stage) =>
        val message = s"Scheduled deploy didn't start because RiffRaff has never deployed $projectName to $stage before. " +
          "Please inform the owner of this schedule as it's likely that they have made a configuration error"
        val scheduledDeployConfig = Action("View scheduled deploy configuration", "https://riffraff.gutools.co.uk/deployment/schedule")
        val contents = NotificationContents(subject, message, List(deployManually, scheduledDeployConfig))
        NotificationContentsWithTargets(contents, riffRaffTargets)
    }
  }

  def deployUnstartedNotification(error: Error): Unit = {
    error.scheduledDeployError.map { failedToStartReason =>
      val contentsWithTargets = notificationContentsWithTargets(failedToStartReason)
      notifyViaAnghammarad(contentsWithTargets.notificationContents, contentsWithTargets.targets)
    }.getOrElse {
      log.warn("Scheduled deploy failed to start but notification was not sent...")
    }
  }

  def deployFailedNotification(uuid: UUID, parameters: DeployParameters, targets: List[Target]): Unit = {

    val notificationContents = NotificationContents(
      subject = s"${parameters.deployer.name} failed",
      message = s"${parameters.deployer.name} for ${parameters.build.projectName} (build ${parameters.build.id}) to stage ${parameters.stage.name} failed.",
      actions = List(Action("View failed deploy", problematicDeployUrl(uuid)))
    )

    notifyViaAnghammarad(notificationContents, targets)

  }

  def scheduledDeploy(deployParameters: DeployParameters): Boolean = deployParameters.deployer == ScheduledDeployer.deployer
  def continuousDeploy(deployParameters: DeployParameters): Boolean = deployParameters.deployer == ContinuousDeployment.deployer

  val messageSub: Subscription = DeployReporter.messages.subscribe(message => {
    message.stack.top match {
      case Fail(_, _) if scheduledDeploy(message.context.parameters) || continuousDeploy(message.context.parameters) =>
        log.info(s"Attempting to send notification via Anghammarad")
        val targets = getTargets(Some(message.context.deployId), Some(message.context.parameters))
        deployFailedNotification(message.context.deployId, message.context.parameters, targets)
      case _ =>
    }
  })

  def init() {}

  def shutdown() {
    messageSub.unsubscribe()
  }
}
