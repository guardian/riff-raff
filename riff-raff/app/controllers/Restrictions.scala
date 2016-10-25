package controllers

import java.util.UUID
import java.util.regex.PatternSyntaxException

import com.gu.googleauth.UserIdentity
import deployment.{RequestSource, UserRequestSource}
import org.joda.time.DateTime
import persistence.RestrictionConfigDynamoRepository
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.ws.WSClient
import play.api.mvc.{Controller, Result}
import restrictions.{RestrictionChecker, RestrictionConfig, RestrictionForm}
import utils.Forms.uuid

class Restrictions()(implicit val messagesApi: MessagesApi, val wsClient: WSClient) extends Controller with LoginActions
  with I18nSupport {

  lazy val restrictionsForm = Form[RestrictionForm](
    mapping(
      "id" -> uuid,
      "projectName" -> nonEmptyText,
      "stage" -> nonEmptyText,
      "editingLocked" -> boolean,
      "whitelist" -> optional(text),
      "continuousDeployment" -> boolean,
      "note" -> nonEmptyText
    )((id, projectName, stage, editingLocked, whitelist, cdPermitted, note) =>
      RestrictionForm(id, projectName, stage, editingLocked,
        whitelist.map(_.split('\n').toSeq.filter(_.nonEmpty)).getOrElse(Seq.empty), cdPermitted, note)
    )(f =>
      Some((f.id, f.projectName, f.stage, f.editingLocked, Some(f.whitelist.mkString("\n")),
        f.continuousDeployment,  f.note))
    ).verifying(
      "Stage is invalid - should be a valid regular expression or contain no special values",
      form => try { form.stage.r; true } catch { case e:PatternSyntaxException => false }
    )
  )

  def list = AuthAction { implicit request =>
    val configs = RestrictionConfigDynamoRepository.getRestrictionList.toList.sortBy(r => r.projectName + r.stage)
    Ok(views.html.restrictions.list(configs))
  }

  def form = AuthAction { implicit request =>
    val newForm = restrictionsForm.fill(
      RestrictionForm(UUID.randomUUID(), "", "", editingLocked = false, Seq.empty, continuousDeployment = false, ""))
    Ok(views.html.restrictions.form(newForm, saveDisabled = false))
  }

  val editableOrForbidden: (Option[RestrictionConfig], UserIdentity) => (=> Result) => Result =
    (maybeConfig, identity) => (ifEditable) =>
    RestrictionChecker.editable[Result](maybeConfig, identity)(ifEditable){ reason =>
      Forbidden(s"Not possible to edit this restriction: $reason")
    }

  def save = AuthAction { implicit request =>
    restrictionsForm.bindFromRequest().fold(
      formWithErrors => Ok(views.html.restrictions.form(formWithErrors, saveDisabled = false)),
      f => {
        editableOrForbidden(RestrictionConfigDynamoRepository.getRestriction(f.id), request.user) {
          val newConfig = RestrictionConfig(f.id, f.projectName, f.stage, new DateTime(), request.user.fullName,
            request.user.email, f.editingLocked, f.whitelist, f.continuousDeployment, f.note)
          RestrictionConfigDynamoRepository.setRestriction(newConfig)
          Redirect(routes.Restrictions.list())
        }
      }
    )
  }
  def edit(id: String) = AuthAction { implicit request =>
    RestrictionConfigDynamoRepository.getRestriction(UUID.fromString(id)).map{ rc =>
      val canSave = RestrictionChecker.editable(Some(rc), request.user)(true){_=>false}
      Ok(views.html.restrictions.form(restrictionsForm.fill(
        RestrictionForm(rc.id, rc.projectName, rc.stage, rc.editingLocked, rc.whitelist, rc.continuousDeployment, rc.note)
      ), saveDisabled = !canSave))
    }.getOrElse(Redirect(routes.Restrictions.list()))
  }
  def delete(id: String) = AuthAction { request =>
    editableOrForbidden(RestrictionConfigDynamoRepository.getRestriction(UUID.fromString(id)), request.user) {
      RestrictionConfigDynamoRepository.deleteRestriction(UUID.fromString(id))
      Redirect(routes.Restrictions.list())
    }
  }
}
