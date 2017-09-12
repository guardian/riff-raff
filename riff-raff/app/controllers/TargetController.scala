package controllers

import ci.Target
import com.gu.googleauth.AuthAction
import deployment.{DeployFilter, Deployments, PaginationView}
import persistence.TargetDynamoRepository
import play.api.i18n.I18nSupport
import play.api.libs.ws.WSClient
import play.api.mvc.{AnyContent, BaseController, ControllerComponents}

class TargetController(deployments: Deployments, authAction: AuthAction[AnyContent], val controllerComponents: ControllerComponents)(implicit val wsClient: WSClient)
  extends BaseController with Logging with I18nSupport {

  def findMatch(region: String, stack: String, app: String) = authAction {
    val targetIds = TargetDynamoRepository.get(Target(region, stack, app))
    targetIds match {
      case Nil => NotFound(s"No project for $region, $stack, $app")
      case nonEmpty => Ok(nonEmpty.mkString("; "))
    }
  }

  def findAppropriateDeploy(region: String, stack: String, app: String, stage: String) = authAction { request =>
    val target = Target(region, stack, app)
    val targetIds = TargetDynamoRepository.get(target)
    targetIds match {
      case singleton :: Nil => Redirect(routes.TargetController.selectRecentVersion(singleton.id, stage))
      case Nil => NotFound(views.html.target.noMatchForTarget(target, request))
      case multiple => Ok(views.html.target.selectTarget(target, multiple.sortBy(-_.lastSeen.getMillis), stage, request))
    }
  }

  def selectRecentVersion(targetId: String, stage: String) = authAction { request =>
    val maybeTargetId = TargetDynamoRepository.get(targetId)
    maybeTargetId.map { targetId =>
      // find recent deploys of this project / stage
      val filter = DeployFilter(projectName = Some(s"^${targetId.projectName}$$"), stage = Some(stage))
      val records = deployments.getDeploys(Some(filter), PaginationView(pageSize = Some(20))).reverse
      Ok(views.html.target.selectVersion(targetId, stage, records, request))
    }.getOrElse(NotFound(s"No target found for $targetId"))
  }
}
