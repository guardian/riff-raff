import ci.{Builds, CIBuildPoller, ContinuousDeployment}
import controllers._
import deployment.preview.PreviewCoordinator
import deployment.{DeploymentEngine, Deployments}
import magenta.deployment_type._
import play.api.ApplicationLoader.Context
import play.api.http.DefaultHttpErrorHandler
import play.api.i18n.I18nComponents
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.Results.InternalServerError
import play.api.mvc.{RequestHeader, Result}
import play.api.routing.Router
import play.api.{BuiltInComponentsFromContext, Logger}
import play.filters.csrf.CSRFComponents
import play.filters.gzip.GzipFilterComponents
import resources.PrismLookup
import utils.HstsFilter

import scala.concurrent.Future
import scala.concurrent.duration._
import router.Routes

class AppComponents(context: Context) extends BuiltInComponentsFromContext(context)
  with AhcWSComponents
  with I18nComponents
  with CSRFComponents
  with GzipFilterComponents
  with AssetsComponents {

  implicit val implicitMessagesApi = messagesApi
  implicit val implicitWsClient = wsClient

  val availableDeploymentTypes = Seq(
    ElasticSearch, S3, AutoScaling, Fastly, CloudFormation, Lambda, AmiCloudFormationParameter, SelfDeploy
  )
  val prismLookup = new PrismLookup(wsClient, conf.Configuration.lookup.prismUrl, conf.Configuration.lookup.timeoutSeconds.seconds)
  val deploymentEngine = new DeploymentEngine(prismLookup, availableDeploymentTypes, conf.Configuration.deprecation.pauseSeconds)
  val buildPoller = new CIBuildPoller(executionContext)
  val builds = new Builds(buildPoller)
  val deployments = new Deployments(deploymentEngine, builds)
  val continuousDeployment = new ContinuousDeployment(buildPoller, deployments)
  val previewCoordinator = new PreviewCoordinator(prismLookup, availableDeploymentTypes)

  override lazy val httpFilters = Seq(
    csrfFilter,
    gzipFilter,
    new HstsFilter()(executionContext)
  ) // TODO (this would require an upgrade of the management-play lib) ++ PlayRequestMetrics.asFilters

  val applicationController = new Application(prismLookup, availableDeploymentTypes, controllerComponents, assets)(environment, wsClient)
  val deployController = new DeployController(deployments, prismLookup, availableDeploymentTypes, builds, controllerComponents)
  val apiController = new Api(deployments, availableDeploymentTypes, controllerComponents)
  val continuousDeployController = new ContinuousDeployController(prismLookup, controllerComponents)
  val previewController = new PreviewController(previewCoordinator, controllerComponents)
  val hooksController = new HooksController(prismLookup, controllerComponents)
  val restrictionsController = new Restrictions(controllerComponents)
  val loginController = new Login(deployments, controllerComponents)
  val testingController = new Testing(prismLookup, controllerComponents)

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
    loginController,
    testingController,
    assets
  )
}
