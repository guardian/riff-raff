package controllers

import ci.Target
import com.gu.googleauth.AuthAction
import conf.Config
import deployment.{DeployFilter, Deployments, PaginationView}
import persistence.TargetDynamoRepository
import play.api.i18n.I18nSupport
import play.api.libs.ws.WSClient
import play.api.mvc.{
  ActionBuilder,
  AnyContent,
  BaseController,
  ControllerComponents
}
import utils.LogAndSquashBehaviour

class TargetController(
    config: Config,
    menu: Menu,
    deployments: Deployments,
    targetDynamoRepository: TargetDynamoRepository,
    authAction: ActionBuilder[AuthAction.UserIdentityRequest, AnyContent],
    val controllerComponents: ControllerComponents
)(implicit val wsClient: WSClient)
    extends BaseController
    with Logging
    with I18nSupport
    with LogAndSquashBehaviour {

  def findMatch(region: String, stack: String, app: String) = authAction {
    val targetIds = targetDynamoRepository.find(Target(region, stack, app))
    targetIds match {
      case Nil      => NotFound(s"No project for $region, $stack, $app")
      case nonEmpty => Ok(nonEmpty.mkString("; "))
    }
  }

  def findAppropriateDeploy(
      region: String,
      stack: String,
      app: String,
      stage: String
  ) = authAction { request =>
    val target = Target(region, stack, app)
    val targetIds = targetDynamoRepository.find(target)
    targetIds match {
      case singleton :: Nil =>
        Redirect(
          routes.TargetController.selectRecentVersion(
            singleton.targetKey,
            singleton.projectName,
            stage
          )
        )
      case Nil =>
        NotFound(
          views.html.deployTarget
            .noMatchForTarget(config, menu)(target, request)
        )
      case multiple =>
        Ok(
          views.html.deployTarget.selectTarget(config, menu)(
            target,
            multiple.sortBy(-_.lastSeen.getMillis),
            stage,
            request
          )
        )
    }
  }

  def selectRecentVersion(
      targetKey: String,
      projectName: String,
      stage: String
  ) = authAction { request =>
    val maybeTargetId = targetDynamoRepository.get(targetKey, projectName)
    maybeTargetId
      .map { targetId =>
        // find recent deploys of this project / stage
        val filter = DeployFilter(
          projectName = Some(targetId.projectName),
          stage = Some(stage),
          isExactMatchProjectName = Some(true)
        )
        val records = deployments
          .getDeploys(Some(filter), PaginationView(pageSize = Some(20)))
          .logAndSquashException(Nil)
          .reverse
        Ok(
          views.html.deployTarget
            .selectVersion(config, menu)(targetId, stage, records, request)
        )
      }
      .getOrElse(NotFound(s"No target found for $targetKey and $projectName"))
  }
}
