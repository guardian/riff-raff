import java.time.Duration
import java.util.function.Supplier

import ci.{Builds, CIBuildPoller, ContinuousDeployment, TargetResolver}
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder
import com.gu.googleauth.AuthAction
import com.gu.play.secretrotation.aws.ParameterStore
import com.gu.play.secretrotation.{RotatingSecretComponents, SecretState, TransitionTiming}
import conf.{Configuration, DeployMetrics}
import controllers._
import deployment.preview.PreviewCoordinator
import deployment.{DeploymentEngine, Deployments}
import housekeeping.ArtifactHousekeeping
import lifecycle.ShutdownWhenInactive
import magenta.deployment_type._
import notification.HooksClient
import persistence.{ScheduleRepository, SummariseDeploysHousekeeping}
import play.api.ApplicationLoader.Context
import play.api.http.DefaultHttpErrorHandler
import play.api.i18n.I18nComponents
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.Results.InternalServerError
import play.api.mvc.{AnyContent, RequestHeader, Result}
import play.api.routing.Router
import play.api.{BuiltInComponentsFromContext, Logger}
import play.filters.csrf.CSRFComponents
import play.filters.gzip.GzipFilterComponents
import resources.PrismLookup
import riffraff.RiffRaffManagementServer
import router.Routes
import schedule.DeployScheduler
import utils.{HstsFilter, ScheduledAgent}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal

class AppComponents(context: Context) extends BuiltInComponentsFromContext(context)
  with RotatingSecretComponents
  with AhcWSComponents
  with I18nComponents
  with CSRFComponents
  with GzipFilterComponents
  with AssetsComponents
  with Logging {

  val secretStateSupplier: Supplier[SecretState] = {
    new ParameterStore.SecretSupplier(
      TransitionTiming(
        usageDelay = Duration.ofMinutes(3),
        overlapDuration = Duration.ofHours(2)
      ),
      conf.Configuration.auth.secretStateSupplierKeyName,
      AWSSimpleSystemsManagementClientBuilder.standard()
        .withRegion(conf.Configuration.auth.secretStateSupplierRegion)
        .withCredentials(Configuration.credentialsProviderChain(None, None))
        .build()
    )
  }

  implicit val implicitMessagesApi = messagesApi
  implicit val implicitWsClient = wsClient


  val availableDeploymentTypes = Seq(
    ElasticSearch, S3, AutoScaling, Fastly, CloudFormation, Lambda, AmiCloudFormationParameter, SelfDeploy
  )
  val prismLookup = new PrismLookup(wsClient, conf.Configuration.lookup.prismUrl, conf.Configuration.lookup.timeoutSeconds.seconds)
  val deploymentEngine = new DeploymentEngine(prismLookup, availableDeploymentTypes, conf.Configuration.deprecation.pauseSeconds)
  val buildPoller = new CIBuildPoller(executionContext)
  val builds = new Builds(buildPoller)
  val targetResolver = new TargetResolver(buildPoller, availableDeploymentTypes)
  val deployments = new Deployments(deploymentEngine, builds)
  val continuousDeployment = new ContinuousDeployment(buildPoller, deployments)
  val previewCoordinator = new PreviewCoordinator(prismLookup, availableDeploymentTypes)
  val artifactHousekeeper = new ArtifactHousekeeping(deployments)

  val authAction = new AuthAction[AnyContent](
    conf.Configuration.auth.googleAuthConfig, routes.Login.loginAction(), controllerComponents.parsers.default)(executionContext)

  override lazy val httpFilters = Seq(
    csrfFilter,
    gzipFilter,
    new HstsFilter()(executionContext)
  ) // TODO (this would require an upgrade of the management-play lib) ++ PlayRequestMetrics.asFilters

  val deployScheduler = new DeployScheduler(deployments)
  log.info("Starting deployment scheduler")
  deployScheduler.start()
  applicationLifecycle.addStopHook { () =>
    log.info("Shutting down deployment scheduler")
    Future.successful(deployScheduler.shutdown())
  }
  deployScheduler.initialise(ScheduleRepository.getScheduleList())

  val hooksClient = new HooksClient(wsClient, executionContext)
  val shutdownWhenInactive = new ShutdownWhenInactive(deployments)

  // the management server takes care of shutting itself down with a lifecycle hook
  val management = new conf.Management(shutdownWhenInactive, deployments)
  val managementServer = new RiffRaffManagementServer(management.applicationName, management.pages, Logger("ManagementServer"))

  val lifecycleSingletons = Seq(
    ScheduledAgent,
    deployments,
    builds,
    targetResolver,
    DeployMetrics,
    hooksClient,
    SummariseDeploysHousekeeping,
    continuousDeployment,
    managementServer,
    shutdownWhenInactive,
    artifactHousekeeper
  )

  log.info(s"Calling init() on Lifecycle singletons: ${lifecycleSingletons.map(_.getClass.getName).mkString(", ")}")
  lifecycleSingletons.foreach(_.init())

  context.lifecycle.addStopHook(() => Future {
    lifecycleSingletons.reverse.foreach { singleton =>
      try {
        singleton.shutdown()
      } catch {
        case NonFatal(e) => log.error("Caught unhandled exception whilst calling shutdown() on Lifecycle singleton", e)
      }
    }
  }(ExecutionContext.global))

  val applicationController = new Application(prismLookup, availableDeploymentTypes, authAction, controllerComponents, assets)(environment, wsClient, executionContext)
  val deployController = new DeployController(deployments, prismLookup, availableDeploymentTypes, builds, authAction, controllerComponents)
  val apiController = new Api(deployments, availableDeploymentTypes, authAction, controllerComponents)
  val continuousDeployController = new ContinuousDeployController(prismLookup, authAction, controllerComponents)
  val previewController = new PreviewController(previewCoordinator, authAction, controllerComponents)(wsClient, executionContext)
  val hooksController = new HooksController(prismLookup, authAction, controllerComponents)
  val restrictionsController = new Restrictions(authAction, controllerComponents)
  val scheduleController = new ScheduleController(authAction, controllerComponents, prismLookup, deployScheduler)
  val targetController = new TargetController(deployments, authAction, controllerComponents)
  val loginController = new Login(deployments, controllerComponents, authAction)
  val testingController = new Testing(prismLookup, authAction, controllerComponents, artifactHousekeeper)

  override lazy val httpErrorHandler = new DefaultHttpErrorHandler(environment, configuration, sourceMapper, Some(router)) {
    override def onServerError(request: RequestHeader, t: Throwable): Future[Result] = {
      Logger.error("Error whilst trying to serve request", t)
      val reportException = if (t.getCause != null) t.getCause else t
      Future.successful(InternalServerError(views.html.errorPage(reportException)))
    }
  }

  override def router: Router = new Routes(
    httpErrorHandler,
    applicationController,
    previewController,
    deployController,
    apiController,
    continuousDeployController,
    hooksController,
    restrictionsController,
    scheduleController,
    targetController,
    loginController,
    testingController,
    assets
  )
}
