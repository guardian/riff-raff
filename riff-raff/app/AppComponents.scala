import java.time.Duration

import ci._
import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder
import software.amazon.awssdk.services.ssm.{SsmClient, SsmClientBuilder}
import com.gu.googleauth.{AntiForgeryChecker, AuthAction, GoogleAuthConfig}
import com.gu.play.secretrotation.aws.ParameterStore
import com.gu.play.secretrotation.{RotatingSecretComponents, SnapshotProvider, TransitionTiming}
import conf.{Config, DeployMetrics, Secrets}
import controllers._
import deployment.preview.PreviewCoordinator
import deployment.{DeploymentEngine, Deployments}
import housekeeping.ArtifactHousekeeping
import lifecycle.ShutdownWhenInactive
import magenta.deployment_type._
import magenta.tasks.AWS
import notification.{DeployFailureNotifications, HooksClient}
import persistence._
import play.api.ApplicationLoader.Context
import play.api.db.evolutions.EvolutionsComponents
import play.api.db.{DBComponents, HikariCPComponents}
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
import software.amazon.awssdk.regions.Region
import utils.{ChangeFreeze, ElkLogging, HstsFilter, ScheduledAgent}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class AppComponents(context: Context, config: Config, passwordProvider: PasswordProvider) extends BuiltInComponentsFromContext(context)
  with RotatingSecretComponents
  with AhcWSComponents
  with I18nComponents
  with CSRFComponents
  with GzipFilterComponents
  with AssetsComponents
  with EvolutionsComponents
  with DBComponents
  with HikariCPComponents
  with Logging {

  lazy val datastore: DataStore = new PostgresDatastoreOps(config, passwordProvider).buildDatastore()

  val secretStateSupplier: SnapshotProvider = {
    new ParameterStore.SecretSupplier(
      transitionTiming = TransitionTiming(
        usageDelay = Duration.ofMinutes(3),
        overlapDuration = Duration.ofHours(2)
      ),
      parameterName = config.auth.secretStateSupplierKeyName,
      ssmClient = AWSSimpleSystemsManagementClientBuilder.standard()
        .withRegion(config.auth.secretStateSupplierRegion)
        .withCredentials(config.credentialsProviderChainV1(None, None))
        .build()
    )
  }

  lazy val googleAuthConfig = GoogleAuthConfig(
    clientId = config.auth.clientId,
    clientSecret = config.auth.clientSecret,
    redirectUrl = config.auth.redirectUrl,
    domain = config.auth.domain,
    antiForgeryChecker = AntiForgeryChecker(secretStateSupplier, AntiForgeryChecker.signatureAlgorithmFromPlay(httpConfiguration))
  )

  //Lazy val needs to be accessed so that database evolutions are applied
  applicationEvolutions

  implicit val implicitMessagesApi = messagesApi
  implicit val implicitWsClient = wsClient

  val elkLogging = new ElkLogging(
    config.stage,
    config.logging.regionName,
    config.logging.elkStreamName,
    config.logging.credentialsProvider,
    applicationLifecycle
  )

  val availableDeploymentTypes = Seq(
    ElasticSearch, S3, AutoScaling, Fastly, CloudFormation, Lambda, AmiCloudFormationParameter, SelfDeploy, GcpDeploymentManager
  )

  val documentStoreConverter = new DocumentStoreConverter(datastore)
  val targetDynamoRepository = new TargetDynamoRepository(config)
  val restrictionConfigDynamoRepository = new RestrictionConfigDynamoRepository(config)
  val s3BuildOps = new S3BuildOps(config)
  val changeFreeze = new ChangeFreeze(config)
  val scheduleRepository = new ScheduleRepository(config)
  val hookConfigRepository = new HookConfigRepository(config)
  val continuousDeploymentConfigRepository = new ContinuousDeploymentConfigRepository(config)
  val menu = new Menu(config)
  val s3Tag = new S3Tag(config)


  val ssmClient = SsmClient.builder()
    .credentialsProvider(config.credentialsProviderChain(None, None))
    .overrideConfiguration(AWS.clientConfiguration)
    .region(Region.of(config.credentials.regionName))
    .build()

  val secretProvider = new Secrets(config, ssmClient)
  secretProvider.populate()
  val prismLookup = new PrismLookup(config, wsClient, secretProvider)
  val deploymentEngine = new DeploymentEngine(config, prismLookup, availableDeploymentTypes)
  val buildPoller = new CIBuildPoller(config, s3BuildOps, executionContext)
  val builds = new Builds(buildPoller)
  val targetResolver = new TargetResolver(config, buildPoller, availableDeploymentTypes, targetDynamoRepository)
  val deployments = new Deployments(deploymentEngine, builds, documentStoreConverter, restrictionConfigDynamoRepository)
  val continuousDeployment = new ContinuousDeployment(config, changeFreeze, buildPoller, deployments, continuousDeploymentConfigRepository)
  val previewCoordinator = new PreviewCoordinator(config,prismLookup, availableDeploymentTypes)
  val artifactHousekeeper = new ArtifactHousekeeping(config, deployments)
  val scheduledDeployNotifier = new DeployFailureNotifications(config, availableDeploymentTypes, targetResolver, prismLookup)

  val authAction = new AuthAction[AnyContent](
    googleAuthConfig, routes.Login.loginAction(), controllerComponents.parsers.default)(executionContext)

  override lazy val httpFilters = Seq(
    csrfFilter,
    gzipFilter,
    new HstsFilter()(executionContext)
  ) // TODO (this would require an upgrade of the management-play lib) ++ PlayRequestMetrics.asFilters

  val deployScheduler = new DeployScheduler(config, deployments)
  log.info("Starting deployment scheduler")
  deployScheduler.start()
  applicationLifecycle.addStopHook { () =>
    log.info("Shutting down deployment scheduler")
    Future.successful(deployScheduler.shutdown())
  }
  deployScheduler.initialise(new ScheduleRepository(config).getScheduleList())

  val hooksClient = new HooksClient(datastore, hookConfigRepository, wsClient, executionContext)
  val shutdownWhenInactive = new ShutdownWhenInactive(deployments)

  // the management server takes care of shutting itself down with a lifecycle hook
  val management = new conf.Management(config, shutdownWhenInactive, deployments, datastore)
  val managementServer = new RiffRaffManagementServer(management.applicationName, management.pages, Logger("ManagementServer"))

  val lifecycleSingletons = Seq(
    ScheduledAgent,
    deployments,
    builds,
    targetResolver,
    DeployMetrics,
    hooksClient,
    new SummariseDeploysHousekeeping(config, datastore),
    continuousDeployment,
    managementServer,
    shutdownWhenInactive,
    artifactHousekeeper,
    scheduledDeployNotifier
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

  val applicationController = new Application(config, menu, prismLookup, availableDeploymentTypes, authAction, controllerComponents, assets)(environment, wsClient, executionContext)
  val deployController = new DeployController(config, menu, deployments, prismLookup, availableDeploymentTypes, changeFreeze, builds, s3Tag, authAction, restrictionConfigDynamoRepository, controllerComponents)
  val apiController = new Api(config, menu, deployments, availableDeploymentTypes, datastore, changeFreeze, authAction, controllerComponents)
  val continuousDeployController = new ContinuousDeployController(config, menu, changeFreeze, prismLookup, authAction, continuousDeploymentConfigRepository, controllerComponents)
  val previewController = new PreviewController(config, menu, previewCoordinator, authAction, controllerComponents)(wsClient, executionContext)
  val hooksController = new HooksController(config, menu, prismLookup, authAction, hookConfigRepository, controllerComponents)
  val restrictionsController = new Restrictions(config, menu, authAction, restrictionConfigDynamoRepository, controllerComponents)
  val scheduleController = new ScheduleController(config, menu, authAction, controllerComponents, scheduleRepository, prismLookup, deployScheduler)
  val targetController = new TargetController(config, menu, deployments, targetDynamoRepository, authAction, controllerComponents)
  val loginController = new Login(config, menu, deployments, datastore, controllerComponents, authAction, googleAuthConfig)
  val testingController = new Testing(config, menu, datastore, prismLookup, documentStoreConverter, authAction, controllerComponents, artifactHousekeeper, deployments)

  override lazy val httpErrorHandler = new DefaultHttpErrorHandler(environment, configuration, sourceMapper, Some(router)) {
    override def onServerError(request: RequestHeader, t: Throwable): Future[Result] = {
      Logger.error("Error whilst trying to serve request", t)
      val reportException = if (t.getCause != null) t.getCause else t
      Future.successful(InternalServerError(views.html.errorPage(config)(reportException)))
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
