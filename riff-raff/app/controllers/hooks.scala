package controllers

import java.net.{MalformedURLException, URL}
import notification.{GET, HttpMethod, HookConfig}
import persistence.Persistence
import play.api.data.{Forms, FormError, Form}
import play.api.data.Forms._
import play.api.data.format.Formatter
import play.api.mvc.Controller
import java.util.UUID
import utils.Forms.uuid
import org.joda.time.DateTime

case class HookForm(id:UUID, projectName: String, stage: String, url: String, enabled: Boolean,
                    method: HttpMethod, postBody: Option[String])

object Hooks extends Controller with Logging with LoginActions {
  implicit val httpMethodFormatter = new Formatter[HttpMethod] {
    override def bind(key: String, data: Map[String, String]): Either[Seq[FormError], HttpMethod] = {
      data.get(key).map { value =>
        Right(HttpMethod(value))
      } getOrElse  (Left(Seq(FormError(key, "error.httpMethod", Nil))))
    }

    override def unbind(key: String, value: HttpMethod): Map[String, String] = Map(key -> value.serialised)
  }

  lazy val hookForm = Form[HookForm](
    mapping(
      "id" -> uuid,
      "projectName" -> nonEmptyText,
      "stage" -> nonEmptyText,
      "url" -> nonEmptyText,
      "enabled" -> boolean,
      "method" -> default(of[HttpMethod], GET),
      "postBody" -> optional(text)
    )( HookForm.apply)(HookForm.unapply ).verifying(
      "URL is invalid", form => try { new URL(form.url); true } catch { case e:MalformedURLException => false }
    )
  )

  def list = AuthAction { implicit request =>
    val hooks = Persistence.store.getPostDeployHookList.toSeq.sortBy(q => (q.projectName, q.stage))
    Ok(views.html.hooks.list(request, hooks))
  }
  def form = AuthAction { implicit request =>
    Ok(views.html.hooks.form(request,hookForm.fill(HookForm(UUID.randomUUID(),"","","",enabled=true, GET, None))))
  }
  def save = AuthAction { implicit request =>
    hookForm.bindFromRequest().fold(
      formWithErrors => Ok(views.html.hooks.form(request,formWithErrors)),
      f => {
        val config = HookConfig(f.id,f.projectName,f.stage,f.url,f.enabled,new DateTime(),request.user.fullName, f.method, f.postBody)
        Persistence.store.setPostDeployHook(config)
        Redirect(routes.Hooks.list())
      }
    )
  }
  def edit(id: String) = AuthAction { implicit request =>
    val uuid = UUID.fromString(id)
    Persistence.store.getPostDeployHook(uuid).map{ hc =>
      Ok(views.html.hooks.form(request,hookForm.fill(HookForm(hc.id,hc.projectName,hc.stage,hc.url,hc.enabled, hc.method, hc.postBody))))
    }.getOrElse(Redirect(routes.Hooks.list()))
  }
  def delete(id: String) = AuthAction { implicit request =>
    Form("action" -> nonEmptyText).bindFromRequest().fold(
      errors => {},
      action => {
        action match {
          case "delete" =>
            Persistence.store.deletePostDeployHook(UUID.fromString(id))
        }
      }
    )
    Redirect(routes.Hooks.list())
  }
}
