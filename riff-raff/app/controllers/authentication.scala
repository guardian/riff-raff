package controllers

import com.gu.googleauth._
import conf.Config
import deployment.{DeployFilter, Deployments, Record}
import org.joda.time.DateTime
import persistence.DataStore
import play.api.i18n.I18nSupport
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.mvc._
import scalikejdbc._
import utils.LogAndSquashBehaviour

import scala.concurrent.ExecutionContext

class ApiRequest[A](val apiKey: ApiKey, request: Request[A])
    extends WrappedRequest[A](request) {
  lazy val fullName = s"API:${apiKey.application}"
}

class Login(
    config: Config,
    menu: Menu,
    deployments: Deployments,
    val controllerComponents: ControllerComponents,
    val authAction: ActionBuilder[AuthAction.UserIdentityRequest, AnyContent],
    val authConfig: GoogleAuthConfig
)(implicit val wsClient: WSClient, val executionContext: ExecutionContext)
    extends BaseController
    with Logging
    with LoginSupport
    with I18nSupport
    with LogAndSquashBehaviour {

  def login = Action { request =>
    val error = request.flash.get("error")
    Ok(views.html.auth.login(config, menu)(request, error))
  }

  def loginAction = Action.async { implicit request =>
    startGoogleLogin()
  }

  def oauth2Callback = Action.async { implicit request =>
    processOauth2Callback()
  }

  def logout = Action { implicit request =>
    Redirect("/").withNewSession
  }

  def profile = authAction { request =>
    val records = deployments
      .getDeploys(Some(DeployFilter(deployer = Some(request.user.fullName))))
      .map(_.reverse)
    records.fold(
      (t: Throwable) =>
        InternalServerError(
          views.html.errorContent(t, "Could not fetch list of deploys")
        ),
      (as: List[Record]) =>
        Ok(views.html.auth.profile(config, menu)(request, as))
    )
  }

  override val failureRedirectTarget: Call = routes.Login.login
  override val defaultRedirectTarget: Call = routes.Application.index
}
