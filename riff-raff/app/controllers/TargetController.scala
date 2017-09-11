package controllers

import ci.Target
import com.gu.googleauth.AuthAction
import persistence.TargetDynamoRepository
import play.api.i18n.I18nSupport
import play.api.libs.ws.WSClient
import play.api.mvc.{AnyContent, BaseController, ControllerComponents}

class TargetController(authAction: AuthAction[AnyContent], val controllerComponents: ControllerComponents)(implicit val wsClient: WSClient)
  extends BaseController with Logging with I18nSupport {

  def findDeployFor(region: String, stack: String, app: String) = authAction {
    val targetProject = TargetDynamoRepository.getId(Target(region, stack, app))
    targetProject.fold(NotFound(s"No project for $region, $stack, $app"))(Ok(_))
  }
}
