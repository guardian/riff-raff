import play.api.ApplicationLoader.Context
import play.api.{BuiltInComponentsFromContext, Logger}
import play.api.http.DefaultHttpErrorHandler
import play.api.i18n.I18nComponents
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{RequestHeader, Result}
import play.api.mvc.Results.InternalServerError
import play.api.routing.Router
import play.filters.csrf.CSRFComponents
import play.filters.gzip.GzipFilterComponents
import resources.PrismLookup
import utils.HstsFilter
import ci.ContinuousDeployment
import controllers._
import deployment.{DeploymentEngine, Deployments}

import scala.concurrent.Future

import router.Routes

class AppComponents(context: Context)
    extends BuiltInComponentsFromContext(context)
    with AhcWSComponents
    with I18nComponents
    with CSRFComponents
    with GzipFilterComponents {

  implicit val implicitMessagesApi = messagesApi
  implicit val implicitWsClient = wsClient

  val prismLookup = new PrismLookup(wsClient)
  val deploymentEngine = new DeploymentEngine(prismLookup)
  val deployments = new Deployments(deploymentEngine)
  val continuousDeployment = new ContinuousDeployment(deployments)

  override lazy val httpFilters = Seq(
    csrfFilter,
    gzipFilter,
    new HstsFilter
  ) // TODO (this would require an upgrade of the management-play lib) ++ PlayRequestMetrics.asFilters

  val applicationController = new Application(prismLookup)(environment, wsClient)
  val deployController = new DeployController(deployments, prismLookup)
  val apiController = new Api(deployments)
  val continuousDeployController = new ContinuousDeployController(prismLookup)
  val hooksController = new Hooks(prismLookup)
  val loginController = new Login
  val testingController = new Testing(prismLookup)
  val assets = new Assets(httpErrorHandler)

  override lazy val httpErrorHandler =
    new DefaultHttpErrorHandler(environment, configuration, sourceMapper, Some(router)) {
      override def onServerError(request: RequestHeader, t: Throwable): Future[Result] = {
        Logger.error("Error whilst trying to serve request", t)
        val reportException = if (t.getCause != null) t.getCause else t
        Future.successful(InternalServerError(views.html.errorPage(reportException)))
      }
    }

  override def router: Router = new Routes(
    httpErrorHandler,
    applicationController,
    deployController,
    apiController,
    continuousDeployController,
    hooksController,
    loginController,
    testingController,
    assets
  )
}
