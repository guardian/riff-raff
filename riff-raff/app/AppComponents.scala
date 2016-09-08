import controllers._
import play.api.ApplicationLoader.Context
import play.api.{BuiltInComponentsFromContext, Logger}
import play.api.http.DefaultHttpErrorHandler
import play.api.i18n.I18nComponents
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{RequestHeader, Result}
import play.api.mvc.Results.InternalServerError
import play.api.routing.Router
import play.filters.gzip.GzipFilter
import utils.HstsFilter

import scala.concurrent.Future

import router.Routes

class AppComponents(context: Context) extends BuiltInComponentsFromContext(context) with AhcWSComponents with I18nComponents {

  implicit val implicitMessagesApi = messagesApi
  implicit val implicitWsClient = wsClient

  override lazy val httpFilters = Seq(
    // TODO CSRF filter
    new GzipFilter,
    new HstsFilter
  ) // TODO this would require an upgrade of the management-play lib ++ PlayRequestMetrics.asFilters

  val applicationController = new Application()(environment, wsClient)
  val deployController = new DeployController
  val apiController = new Api
  val continuousDeployController = new ContinuousDeployController
  val hooksController = new Hooks
  val loginController = new Login
  val testingController = new Testing
  val assets = new Assets(httpErrorHandler)

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
    deployController,
    apiController,
    continuousDeployController,
    hooksController,
    loginController,
    testingController,
    assets
  )
}
