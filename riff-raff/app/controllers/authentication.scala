package controllers

import cats.data.EitherT
import com.gu.googleauth._
import conf.Config
import deployment.{DeployFilter, Deployments, Record}
import org.joda.time.DateTime
import persistence.DataStore
import play.api.data.Forms._
import play.api.data._
import play.api.i18n.I18nSupport
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.mvc._
import scalikejdbc._
import utils.Json._
import utils.LogAndSquashBehaviour

import scala.concurrent.{ExecutionContext, Future}

class ApiRequest[A](val apiKey: ApiKey, request: Request[A])
    extends WrappedRequest[A](request) {
  lazy val fullName = s"API:${apiKey.application}"
}

case class AuthorisationRecord(
    email: String,
    approvedBy: String,
    approvedDate: DateTime
) {
  def contentBlob = (approvedBy, approvedDate)

  def isSuperuser(config: Config) = config.auth.superusers.contains(email)
}
object AuthorisationRecord extends SQLSyntaxSupport[ApiKey] {
  implicit val formats: Format[AuthorisationRecord] =
    Json.format[AuthorisationRecord]

  def apply(res: WrappedResultSet): AuthorisationRecord =
    Json.parse(res.string(1)).as[AuthorisationRecord]

  override val tableName = "auth"
}

trait AuthorisationValidator {
  def emailDomainAllowList: List[String]
  def emailAllowListEnabled: Boolean
  def emailAllowListContains(email: String): Boolean
  def isAuthorised(id: UserIdentity) = authorisationError(id).isEmpty
  def authorisationError(id: UserIdentity): Option[String] = {
    if (
      emailDomainAllowList.nonEmpty && !emailDomainAllowList.contains(
        id.emailDomain
      )
    ) {
      Some(
        s"The e-mail address domain you used to login to Riff-Raff (${id.email}) is not in the configured allowlist.  Please try again with another account or contact the Riff-Raff administrator."
      )
    } else if (emailAllowListEnabled && !emailAllowListContains(id.email)) {
      Some(
        s"The e-mail address you used to login to Riff-Raff (${id.email}) is not authorised.  Please try again with another account, ask a colleague to add your address or contact the Riff-Raff administrator."
      )
    } else {
      None
    }
  }
}

class Login(
    config: Config,
    menu: Menu,
    deployments: Deployments,
    datastore: DataStore,
    val controllerComponents: ControllerComponents,
    val authAction: ActionBuilder[AuthAction.UserIdentityRequest, AnyContent],
    val authConfig: GoogleAuthConfig
)(implicit val wsClient: WSClient, val executionContext: ExecutionContext)
    extends BaseController
    with Logging
    with LoginSupport
    with I18nSupport
    with LogAndSquashBehaviour {

  val validator = new AuthorisationValidator {
    def emailDomainAllowList = config.auth.domains
    def emailAllowListEnabled =
      config.auth.allowList.useDatabase || config.auth.allowList.addresses.nonEmpty
    def emailAllowListContains(email: String) = {
      val lowerCaseEmail = email.toLowerCase
      config.auth.allowList.addresses.contains(lowerCaseEmail) ||
      (config.auth.allowList.useDatabase && datastore
        .getAuthorisation(lowerCaseEmail)
        .exists(_.isDefined))
    }
  }

  def login = Action { request =>
    val error = request.flash.get("error")
    Ok(views.html.auth.login(config, menu)(request, error))
  }

  def loginAction = Action.async { implicit request =>
    startGoogleLogin()
  }

  def oauth2Callback = Action.async { implicit request =>
    import cats.instances.future._
    (for {
      identity <- checkIdentity()
      _ <- EitherT.fromEither[Future] {
        if (validator.isAuthorised(identity)) Right(())
        else
          Left(
            redirectWithError(
              failureRedirectTarget,
              validator.authorisationError(identity).getOrElse("Unknown error")
            )
          )
      }
    } yield {
      setupSessionWhenSuccessful(identity)
    }).merge
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

  val authorisationForm = Form("email" -> nonEmptyText)

  def authList = authAction { request =>
    datastore.getAuthorisationList
      .map(_.sortBy(_.email))
      .fold(
        (t: Throwable) =>
          InternalServerError(
            views.html.errorContent(t, "Could not fetch authorisation list")
          ),
        (as: Seq[AuthorisationRecord]) =>
          Ok(views.html.auth.list(config, menu)(request, as))
      )
  }

  def authForm = authAction { implicit request =>
    Ok(views.html.auth.form(config, menu)(authorisationForm))
  }

  def authSave = authAction { implicit request =>
    authorisationForm
      .bindFromRequest()
      .fold(
        errors => BadRequest(views.html.auth.form(config, menu)(errors)),
        email => {
          val auth = AuthorisationRecord(
            email.toLowerCase.trim,
            request.user.fullName,
            new DateTime()
          )
          datastore.setAuthorisation(auth)
          Redirect(routes.Login.authList)
        }
      )
  }

  def authDelete = authAction { implicit request =>
    authorisationForm
      .bindFromRequest()
      .fold(
        _ => {},
        email => {
          log.info(s"${request.user.fullName} deleted authorisation for $email")
          datastore.deleteAuthorisation(email)
        }
      )
    Redirect(routes.Login.authList)
  }

  override val failureRedirectTarget: Call = routes.Login.login
  override val defaultRedirectTarget: Call = routes.Application.index
}
