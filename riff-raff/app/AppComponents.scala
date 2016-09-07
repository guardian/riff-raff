import controllers._
import play.api.ApplicationLoader.Context
import play.api.BuiltInComponentsFromContext
import play.api.i18n.I18nComponents
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.routing.Router
import router.Routes

class AppComponents(context: Context) extends BuiltInComponentsFromContext(context) with AhcWSComponents with I18nComponents {

  implicit val implicitMessagesApi = messagesApi
  implicit val implicitWsClient = wsClient

  val applicationController = new Application()(environment, wsClient)
  val deployController = new DeployController
  val apiController = new Api
  val continuousDeployController = new ContinuousDeployController
  val hooksController = new Hooks
  val loginController = new Login
  val testingController = new Testing
  val assets = new Assets(httpErrorHandler)

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
