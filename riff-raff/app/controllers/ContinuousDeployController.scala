package controllers

import play.api.mvc.Controller
import play.api.data.Forms._
import play.api.data.Form
import persistence.Persistence
import java.util.UUID
import ci.{Trigger, ContinuousDeploymentConfig}
import org.joda.time.DateTime
import utils.Forms.uuid

object ContinuousDeployController extends Controller with Logging {

  case class ConfigForm(id: UUID, projectName: String, stage: String, recipe: String, branchMatcher:Option[String], trigger: Int)
  object ConfigForm {
    def apply(cd: ContinuousDeploymentConfig): ConfigForm =
      ConfigForm(cd.id, cd.projectName, cd.stage, cd.recipe, cd.branchMatcher, cd.trigger.id)
  }

  val continuousDeploymentForm = Form[ConfigForm](
    mapping(
      "id" -> uuid,
      "projectName" -> nonEmptyText,
      "stage" -> nonEmptyText,
      "recipe" -> nonEmptyText,
      "branchMatcher" -> optional(text),
      "trigger" -> number
    )(ConfigForm.apply)(ConfigForm.unapply)
  )

  def list = AuthAction { implicit request =>
    val configs = Persistence.store.getContinuousDeploymentList.toSeq.sortBy(q => (q.projectName, q.stage))
    Ok(views.html.continuousDeployment.list(request, configs))
  }
  def form = AuthAction { implicit request =>
    Ok(views.html.continuousDeployment.form(request,continuousDeploymentForm.fill(ConfigForm(UUID.randomUUID(),"","","default",None,Trigger.SuccessfulBuild.id))))
  }
  def save = AuthAction { implicit request =>
    continuousDeploymentForm.bindFromRequest().fold(
      formWithErrors => Ok(views.html.continuousDeployment.form(request,formWithErrors)),
      form => {
        val config = ContinuousDeploymentConfig(
          form.id, form.projectName, form.stage, form.recipe, form.branchMatcher, Trigger(form.trigger), request.identity.get.fullName, new DateTime()
        )
        Persistence.store.setContinuousDeployment(config)
        Redirect(routes.ContinuousDeployController.list())
      }
    )
  }
  def edit(id: String) = AuthAction { implicit request =>
    Persistence.store.getContinuousDeployment(UUID.fromString(id)).map{ config =>
      Ok(views.html.continuousDeployment.form(request,continuousDeploymentForm.fill(ConfigForm(config))))
    }.getOrElse(Redirect(routes.ContinuousDeployController.list()))
  }
  def delete(id: String) = AuthAction { implicit request =>
    Form("action" -> nonEmptyText).bindFromRequest().fold(
      errors => {},
      action => {
        action match {
          case "delete" =>
            Persistence.store.deleteContinuousDeployment(UUID.fromString(id))
        }
      }
    )
    Redirect(routes.ContinuousDeployController.list())
  }
}
