package controllers

import java.util.UUID
import com.gu.googleauth.AuthAction
import conf.Config
import deployment.Error
import org.joda.time.DateTime
import persistence.RestrictionConfigDynamoRepository
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.I18nSupport
import play.api.libs.ws.WSClient
import play.api.mvc.{
  ActionBuilder,
  AnyContent,
  BaseController,
  ControllerComponents
}
import restrictions.{RestrictionChecker, RestrictionConfig, RestrictionForm}

import scala.util.Try

class Restrictions(
    config: Config,
    menu: Menu,
    authAction: ActionBuilder[AuthAction.UserIdentityRequest, AnyContent],
    restrictionConfigDynamoRepository: RestrictionConfigDynamoRepository,
    val controllerComponents: ControllerComponents
)(implicit val wsClient: WSClient)
    extends BaseController
    with I18nSupport {

  lazy val restrictionsForm = Form[RestrictionForm](
    mapping(
      "id" -> uuid,
      "projectName" -> nonEmptyText,
      "stage" -> nonEmptyText,
      "editingLocked" -> boolean,
      "allowlist" -> optional(text),
      "continuousDeployment" -> boolean,
      "note" -> nonEmptyText
    )((id, projectName, stage, editingLocked, allowlist, cdPermitted, note) =>
      RestrictionForm(
        id,
        projectName,
        stage,
        editingLocked,
        allowlist
          .map(_.split('\n').map(_.trim).toSeq.filter(_.nonEmpty))
          .getOrElse(Seq.empty),
        cdPermitted,
        note
      )
    )(f =>
      Some(
        (
          f.id,
          f.projectName,
          f.stage,
          f.editingLocked,
          Some(f.allowlist.mkString("\n")),
          f.continuousDeployment,
          f.note
        )
      )
    ).verifying(
      "Stage is invalid - should be a valid regular expression or contain no special values",
      form => Try(form.stage.r).isSuccess
    )
  )

  def list = authAction { implicit request =>
    val configs =
      restrictionConfigDynamoRepository.getRestrictionList.toList.sortBy(r =>
        r.projectName + r.stage
      )
    Ok(views.html.restrictions.list(config, menu)(configs))
  }

  def form = authAction { implicit request =>
    val newForm = restrictionsForm.fill(
      RestrictionForm(
        UUID.randomUUID(),
        "",
        "",
        editingLocked = false,
        Seq.empty,
        continuousDeployment = false,
        ""
      )
    )
    Ok(
      views.html.restrictions.form(config, menu)(newForm, saveDisabled = false)
    )
  }

  def save = authAction { implicit request =>
    restrictionsForm
      .bindFromRequest()
      .fold(
        formWithErrors =>
          Ok(
            views.html.restrictions
              .form(config, menu)(formWithErrors, saveDisabled = false)
          ),
        f => {
          RestrictionChecker.isEditable(
            restrictionConfigDynamoRepository.getRestriction(f.id),
            request.user,
            config.auth.superusers
          ) match {
            case Right(_) =>
              val newConfig = RestrictionConfig(
                f.id,
                f.projectName,
                f.stage,
                new DateTime(),
                request.user.fullName,
                request.user.email,
                f.editingLocked,
                f.allowlist,
                f.continuousDeployment,
                f.note
              )
              restrictionConfigDynamoRepository.setRestriction(newConfig)
              Redirect(routes.Restrictions.list)
            case Left(Error(reason)) =>
              Forbidden(s"Not possible to update this restriction: $reason")
          }
        }
      )
  }

  def edit(id: String) = authAction { implicit request =>
    restrictionConfigDynamoRepository
      .getRestriction(UUID.fromString(id))
      .map { rc =>
        val form = restrictionsForm.fill(
          RestrictionForm(
            rc.id,
            rc.projectName,
            rc.stage,
            rc.editingLocked,
            rc.allowlist,
            rc.continuousDeployment,
            rc.note
          )
        )
        val cannotSave = RestrictionChecker
          .isEditable(Some(rc), request.user, config.auth.superusers)
          .isLeft

        Ok(
          views.html.restrictions.form(config, menu)(
            restrictionForm = form,
            saveDisabled = cannotSave
          )
        )
      }
      .getOrElse(Redirect(routes.Restrictions.list))
  }

  def delete(id: String) = authAction { request =>
    RestrictionChecker.isEditable(
      restrictionConfigDynamoRepository.getRestriction(UUID.fromString(id)),
      request.user,
      config.auth.superusers
    ) match {
      case Right(_) =>
        restrictionConfigDynamoRepository.deleteRestriction(UUID.fromString(id))
        Redirect(routes.Restrictions.list)
      case Left(Error(reason)) =>
        Forbidden(s"Not possible to delete this restriction: $reason")
    }
  }
}
