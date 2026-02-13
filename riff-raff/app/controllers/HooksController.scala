package controllers

import java.net.{MalformedURLException, URI, URL}
import java.util.UUID
import conf.Config
import com.gu.googleauth.AuthAction
import notification.{GET, HookConfig, HttpMethod}
import org.joda.time.DateTime
import persistence.HookConfigRepository
import play.api.data.Forms._
import play.api.data.format.Formatter
import play.api.data.{Form, FormError}
import play.api.i18n.I18nSupport
import play.api.libs.ws.WSClient
import play.api.mvc.{
  ActionBuilder,
  AnyContent,
  BaseController,
  ControllerComponents
}
import resources.PrismLookup

case class HookForm(
    id: UUID,
    projectName: String,
    stage: String,
    url: String,
    enabled: Boolean,
    method: HttpMethod,
    postBody: Option[String]
)

class HooksController(
    config: Config,
    menu: Menu,
    prismLookup: PrismLookup,
    authAction: ActionBuilder[AuthAction.UserIdentityRequest, AnyContent],
    hookConfigRepository: HookConfigRepository,
    val controllerComponents: ControllerComponents
)(implicit val wsClient: WSClient)
    extends BaseController
    with Logging
    with I18nSupport {

  implicit val httpMethodFormatter = new Formatter[HttpMethod] {
    override def bind(
        key: String,
        data: Map[String, String]
    ): Either[Seq[FormError], HttpMethod] = {
      data.get(key).map { value =>
        Right(HttpMethod(value))
      } getOrElse Left(Seq(FormError(key, "error.httpMethod", Nil)))
    }

    override def unbind(key: String, value: HttpMethod): Map[String, String] =
      Map(key -> value.serialised)
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
    )(HookForm.apply)(HookForm.unapply).verifying(
      "URL is invalid",
      form =>
        try { URI.create(form.url).toURL; true }
        catch {
          case _: MalformedURLException | _: IllegalArgumentException => false
        }
    )
  )

  def list = authAction { implicit request =>
    val hooks = hookConfigRepository.getPostDeployHookList.toSeq.sortBy(q =>
      (q.projectName, q.stage)
    )
    Ok(views.html.hooks.list(config, menu)(request, hooks))
  }

  def form = authAction { implicit request =>
    Ok(
      views.html.hooks.form(config, menu)(
        hookForm.fill(
          HookForm(UUID.randomUUID(), "", "", "", enabled = true, GET, None)
        ),
        prismLookup
      )
    )
  }

  def save = authAction { implicit request =>
    hookForm
      .bindFromRequest()
      .fold(
        formWithErrors =>
          Ok(views.html.hooks.form(config, menu)(formWithErrors, prismLookup)),
        f => {
          val config = HookConfig(
            f.id,
            f.projectName,
            f.stage,
            f.url,
            f.enabled,
            new DateTime(),
            request.user.fullName,
            f.method,
            f.postBody
          )
          hookConfigRepository.setPostDeployHook(config)
          Redirect(routes.HooksController.list)
        }
      )
  }

  def edit(id: String) = authAction { implicit request =>
    val uuid = UUID.fromString(id)
    hookConfigRepository
      .getPostDeployHook(uuid)
      .map { hc =>
        Ok(
          views.html.hooks.form(config, menu)(
            hookForm.fill(
              HookForm(
                hc.id,
                hc.projectName,
                hc.stage,
                hc.url,
                hc.enabled,
                hc.method,
                hc.postBody
              )
            ),
            prismLookup
          )
        )
      }
      .getOrElse(Redirect(routes.HooksController.list))
  }

  def delete(id: String) = authAction { implicit request =>
    Form("action" -> nonEmptyText)
      .bindFromRequest()
      .fold(
        errors => {},
        { case "delete" =>
          hookConfigRepository.deletePostDeployHook(UUID.fromString(id))
        }
      )
    Redirect(routes.HooksController.list)
  }
}
