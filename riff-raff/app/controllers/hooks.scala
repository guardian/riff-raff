package controllers

import java.net.{MalformedURLException, URL}
import notification.{HookAction, HookCriteria}
import persistence.Persistence
import play.api.data.Form
import play.api.data.Forms._
import play.api.mvc.Controller

case class HookForm(criteria: HookCriteria, action: HookAction)

object Hooks extends Controller with Logging {

  lazy val hookCriteriaMapping = mapping[HookCriteria,String,String]( "projectName" -> nonEmptyText,
    "stage" -> nonEmptyText
  )( HookCriteria.apply )( HookCriteria.unapply )

  lazy val hookCriteriaForm = Form[HookCriteria](
    hookCriteriaMapping
  )

  lazy val hookForm = Form[HookForm](
    mapping(
      "criteria" -> hookCriteriaMapping,
      "action" -> mapping(
        "url" -> nonEmptyText,
        "enabled" -> boolean
      )(HookAction.apply)(HookAction.unapply)
    )( HookForm.apply)(HookForm.unapply ).verifying(
      "URL is invalid", form => try { new URL(form.action.url); true } catch { case e:MalformedURLException => false }
    )
  )

  def list = AuthAction { implicit request =>
    val hooks = Persistence.store.getPostDeployHooks.toSeq.sortBy(q => (q._1.projectName, q._1.stage))
    Ok(views.html.hooks.list(request, hooks))
  }
  def form = AuthAction { implicit request =>
    Ok(views.html.hooks.form(request,hookForm.fill(HookForm(HookCriteria("", ""), HookAction("",true)))))
  }
  def save = AuthAction { implicit request =>
    hookForm.bindFromRequest().fold(
      formWithErrors => Ok(views.html.hooks.form(request,formWithErrors)),
      form => {
        Persistence.store.setPostDeployHook(form.criteria, form.action)
        Redirect(routes.Hooks.list())
      }
    )
  }
  def edit(projectName: String, stage: String) = AuthAction { implicit request =>
    val criteria = HookCriteria(projectName, stage)
    Persistence.store.getPostDeployHook(criteria).map{ action =>
      Ok(views.html.hooks.form(request,hookForm.fill(HookForm(criteria,action))))
    }.getOrElse(Redirect(routes.Hooks.list()))
  }
  def delete = AuthAction { implicit request =>
    hookCriteriaForm.bindFromRequest().fold(
      errors => {},
      deleteCriteria => { Persistence.store.deletePostDeployHook(deleteCriteria) }
    )
    Redirect(routes.Hooks.list())
  }
}
