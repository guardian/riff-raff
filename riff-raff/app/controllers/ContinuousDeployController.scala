package controllers

import play.api.mvc.{
  ActionBuilder,
  AnyContent,
  BaseController,
  ControllerComponents
}
import play.api.data.Forms._
import play.api.data.Form
import play.api.i18n.I18nSupport
import play.api.libs.ws.WSClient

import java.util.UUID
import org.joda.time.DateTime
import ci.{ContinuousDeploymentConfig, Trigger}
import com.gu.googleauth.AuthAction
import conf.Config
import persistence.ContinuousDeploymentConfigRepository
import resources.PrismLookup
import utils.ChangeFreeze

class ContinuousDeployController(
    config: Config,
    menu: Menu,
    changeFreeze: ChangeFreeze,
    prismLookup: PrismLookup,
    authAction: ActionBuilder[AuthAction.UserIdentityRequest, AnyContent],
    continuousDeploymentConfigRepository: ContinuousDeploymentConfigRepository,
    val controllerComponents: ControllerComponents
)(implicit val wsClient: WSClient)
    extends BaseController
    with Logging
    with I18nSupport {
  import ContinuousDeployController._

  val continuousDeploymentForm = Form[ConfigForm](
    mapping(
      "id" -> uuid,
      "projectName" -> nonEmptyText,
      "stage" -> nonEmptyText,
      "branchMatcher" -> optional(text),
      "trigger" -> number
    )(ConfigForm.apply)(ConfigForm.unapply)
  )

  def list = authAction { implicit request =>
    val configs = continuousDeploymentConfigRepository
      .getContinuousDeploymentList()
      .sortBy(q => (q.projectName, q.stage))
    Ok(
      views.html.continuousDeployment
        .list(config, menu)(changeFreeze)(request, configs)
    )
  }

  def form = authAction { implicit request =>
    Ok(
      views.html.continuousDeployment.form(config, menu)(
        continuousDeploymentForm.fill(
          ConfigForm(
            UUID.randomUUID(),
            "",
            "",
            None,
            Trigger.SuccessfulBuild.id
          )
        ),
        prismLookup
      )
    )
  }

  def save = authAction { implicit request =>
    continuousDeploymentForm
      .bindFromRequest()
      .fold(
        formWithErrors =>
          Ok(
            views.html.continuousDeployment
              .form(config, menu)(formWithErrors, prismLookup)
          ),
        form => {
          val config = ContinuousDeploymentConfig(
            form.id,
            form.projectName,
            form.stage,
            form.branchMatcher,
            Trigger(form.trigger),
            request.user.fullName,
            new DateTime()
          )
          continuousDeploymentConfigRepository.setContinuousDeployment(config)
          Redirect(routes.ContinuousDeployController.list)
        }
      )
  }

  def edit(id: String) = authAction { implicit request =>
    continuousDeploymentConfigRepository
      .getContinuousDeployment(UUID.fromString(id))
      .map { deploymentConfig =>
        Ok(
          views.html.continuousDeployment.form(config, menu)(
            continuousDeploymentForm.fill(ConfigForm(deploymentConfig)),
            prismLookup
          )
        )
      }
      .getOrElse(Redirect(routes.ContinuousDeployController.list))
  }

  def delete(id: String) = authAction { implicit request =>
    Form("action" -> nonEmptyText)
      .bindFromRequest()
      .fold(
        errors => {},
        { case "delete" =>
          continuousDeploymentConfigRepository
            .deleteContinuousDeployment(UUID.fromString(id))
        }
      )
    Redirect(routes.ContinuousDeployController.list)
  }

}

object ContinuousDeployController {

  case class ConfigForm(
      id: UUID,
      projectName: String,
      stage: String,
      branchMatcher: Option[String],
      trigger: Int
  )
  object ConfigForm {
    def apply(cd: ContinuousDeploymentConfig): ConfigForm =
      ConfigForm(
        cd.id,
        cd.projectName,
        cd.stage,
        cd.branchMatcher,
        cd.trigger.id
      )
  }

}
