package controllers

import play.api.mvc.Controller
import play.api.data.Forms._
import play.api.data.Form
import persistence.Persistence
import java.util.UUID
import ci.{ContinuousDeployment, Trigger, ContinuousDeploymentConfig}
import org.joda.time.DateTime
import utils.Forms.uuid

object ContinuousDeployController extends Controller with Logging {

  case class ConfigForm(id: UUID, projectName: String, stage: String, recipe: String, branchMatcher:Option[String], trigger: Int, tag: Option[String])
  object ConfigForm {
    def apply(cd: ContinuousDeploymentConfig): ConfigForm =
      ConfigForm(cd.id, cd.projectName, cd.stage, cd.recipe, cd.branchMatcher, cd.trigger.id, cd.tag)
  }

  val continuousDeploymentForm = Form[ConfigForm](
    mapping(
      "id" -> uuid,
      "projectName" -> nonEmptyText,
      "stage" -> nonEmptyText,
      "recipe" -> nonEmptyText,
      "branchMatcher" -> optional(text),
      "trigger" -> number,
      "tag" -> optional(text)
    )(ConfigForm.apply)(ConfigForm.unapply).verifying(
      error = s"Tag must be specified when trigger is ${Trigger.BuildTagged}",
      constraint = { config => if (config.trigger == Trigger.BuildTagged.id) config.tag.isDefined else true }
    )
  )

  def list = AuthAction { implicit request =>
    val configs = Persistence.store.getContinuousDeploymentList.toSeq.sortBy(q => (q.projectName, q.stage))
    Ok(views.html.continuousDeployment.list(request, configs))
  }
  def form = AuthAction { implicit request =>
    Ok(views.html.continuousDeployment.form(request,continuousDeploymentForm.fill(ConfigForm(UUID.randomUUID(),"","","default",None,Trigger.SuccessfulBuild.id,None))))
  }
  def save = AuthAction { implicit request =>
    continuousDeploymentForm.bindFromRequest().fold(
      formWithErrors => Ok(views.html.continuousDeployment.form(request,formWithErrors)),
      form => {
        val config = ContinuousDeploymentConfig(
          form.id, form.projectName, form.stage, form.recipe, form.branchMatcher, Trigger(form.trigger), form.tag, request.identity.get.fullName, new DateTime()
        )
        Persistence.store.setContinuousDeployment(config)
        ContinuousDeployment.updateTagTrackers()
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
            ContinuousDeployment.updateTagTrackers()
        }
      }
    )
    Redirect(routes.ContinuousDeployController.list())
  }
}
