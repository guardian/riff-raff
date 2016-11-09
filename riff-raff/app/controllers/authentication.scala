package controllers

import com.gu.googleauth._
import com.mongodb.casbah.commons.MongoDBObject
import conf.Configuration.auth
import conf._
import deployment.{DeployFilter, Deployments}
import org.joda.time.DateTime
import persistence.{MongoFormat, MongoSerialisable, Persistence}
import play.api.data.Forms._
import play.api.data._
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc.BodyParsers._
import play.api.mvc.Results._
import play.api.mvc._

import scala.concurrent.Future

class ApiRequest[A](val apiKey: ApiKey, request: Request[A]) extends WrappedRequest[A](request) {
  lazy val fullName = s"API:${apiKey.application}"
}

case class AuthorisationRecord(email: String, approvedBy: String, approvedDate: DateTime)
object AuthorisationRecord extends MongoSerialisable[AuthorisationRecord] {
  implicit val authFormat:MongoFormat[AuthorisationRecord] = new AuthMongoFormat
  private class AuthMongoFormat extends MongoFormat[AuthorisationRecord] {
    def toDBO(a: AuthorisationRecord) = MongoDBObject("_id" -> a.email, "approvedBy" -> a.approvedBy, "approvedDate" -> a.approvedDate)
    def fromDBO(dbo: MongoDBObject) = Some(AuthorisationRecord(dbo.as[String]("_id"), dbo.as[String]("approvedBy"), dbo.as[DateTime]("approvedDate")))
  }
}

trait AuthorisationValidator {
  def emailDomainWhitelist: List[String]
  def emailWhitelistEnabled: Boolean
  def emailWhitelistContains(email:String): Boolean
  def isAuthorised(id: UserIdentity) = authorisationError(id).isEmpty
  def authorisationError(id: UserIdentity): Option[String] = {
    if (!emailDomainWhitelist.isEmpty && !emailDomainWhitelist.contains(id.emailDomain)) {
      Some(s"The e-mail address domain you used to login to Riff-Raff (${id.email}) is not in the configured whitelist.  Please try again with another account or contact the Riff-Raff administrator.")
    } else if (emailWhitelistEnabled && !emailWhitelistContains(id.email)) {
      Some(s"The e-mail address you used to login to Riff-Raff (${id.email}) is not authorised.  Please try again with another account, ask a colleague to add your address or contact the Riff-Raff administrator.")
    } else {
      None
    }
  }
}

object ApiAuthAction {

  def apply[A](counter: Option[String], p: BodyParser[A])(f: ApiRequest[A] => Result): Action[A]  = {
    Action(p) { implicit request =>
      request.queryString.get("key").flatMap(_.headOption) match {
        case Some(urlParam) =>
          Persistence.store.getAndUpdateApiKey(urlParam, counter) match {
            case Some(apiKey) => f(new ApiRequest(apiKey, request))
            case None => Unauthorized("The API key provided is not valid. Please check and try again.")
          }
        case None =>
          Unauthorized("An API key must be provided for this endpoint. Please include a 'key' URL parameter.")
      }
    }
  }

  def apply[A](counter: String, p: BodyParser[A])(f: ApiRequest[A] => Result): Action[A]  =
    this.apply(Some(counter), p)(f)
  def apply[A](p: BodyParser[A])(f: ApiRequest[A] => Result): Action[A] =
    this.apply(None, p)(f)
  def apply(counter: Option[String])(f: ApiRequest[AnyContent] => Result): Action[AnyContent] =
    this.apply(counter, parse.anyContent)(f)
  def apply(counter: String)(f: ApiRequest[AnyContent] => Result): Action[AnyContent] =
    this.apply(Some(counter))(f)
  def apply(f: ApiRequest[AnyContent] => Result): Action[AnyContent] =
    this.apply(None)(f)
}

trait LoginActions extends Actions {
  override def loginTarget: Call = routes.Login.loginAction()
  override val defaultRedirectTarget = routes.Application.index()
  override val failureRedirectTarget = routes.Login.login()

  def authConfig: GoogleAuthConfig = auth.googleAuthConfig
}

class Login(implicit val messagesApi: MessagesApi, val wsClient: WSClient) extends Controller with Logging with LoginActions with I18nSupport {

  val validator = new AuthorisationValidator {
    def emailDomainWhitelist = auth.domains
    def emailWhitelistEnabled = auth.whitelist.useDatabase || auth.whitelist.addresses.nonEmpty
    def emailWhitelistContains(email: String) = {
      val lowerCaseEmail = email.toLowerCase
      auth.whitelist.addresses.contains(lowerCaseEmail) ||
        (auth.whitelist.useDatabase && Persistence.store.getAuthorisation(lowerCaseEmail).isDefined)
    }
  }

  def login = Action { request =>
    val error = request.flash.get("error")
    Ok(views.html.auth.login(request, error))
  }

  def loginAction = Action.async { implicit request =>
    // redirect to google with anti forgery token (that we keen in session storage - note that flashing is not secure)
    val antiForgeryToken = GoogleAuth.generateAntiForgeryToken()
    GoogleAuth.redirectToGoogle(auth.googleAuthConfig, antiForgeryToken).map {
      _.withSession { request.session + (authConfig.antiForgeryKey -> antiForgeryToken) }
    }
  }

  def oauth2Callback = Action.async { implicit request =>
    request.session.get(authConfig.antiForgeryKey) match {
      case None =>
        Future.successful(Redirect(routes.Login.login()).flashing("error" -> "Anti forgery token missing in session"))
      case Some(token) =>
        GoogleAuth.validatedUserIdentity(auth.googleAuthConfig, token).map { identity =>
          require(validator.isAuthorised(identity), validator.authorisationError(identity).getOrElse("Unknown error"))
          val redirect = request.session.get(GoogleAuthFilters.LOGIN_ORIGIN_KEY) match {
            case Some(url) => Redirect(url)
            case None => Redirect(routes.Application.index())
          }
          redirect.withSession {
            request.session + (UserIdentity.KEY -> Json.toJson(identity).toString) - authConfig.antiForgeryKey - GoogleAuthFilters.LOGIN_ORIGIN_KEY
          }
        } recover {
          case t =>
            FailedLoginCounter.recordCount(1)
            log.warn("Login failure", t)
            Redirect(routes.Login.login())
              .withSession(request.session - authConfig.antiForgeryKey)
              .flashing("error" -> s"Login failure: ${t.toString}")
        }
    }
  }

  def logout = Action { implicit request =>
    Redirect("/").withNewSession
  }

  def profile = AuthAction { request =>
    val records = Deployments.getDeploys(Some(DeployFilter(deployer=Some(request.user.fullName)))).reverse
    Ok(views.html.auth.profile(request, records))
  }

  val authorisationForm = Form( "email" -> nonEmptyText )

  def authList = AuthAction { request =>
    Ok(views.html.auth.list(request, Persistence.store.getAuthorisationList.sortBy(_.email)))
  }

  def authForm = AuthAction { implicit request =>
    Ok(views.html.auth.form(authorisationForm))
  }

  def authSave = AuthAction { implicit request =>
    authorisationForm.bindFromRequest().fold(
      errors => BadRequest(views.html.auth.form(errors)),
      email => {
        val auth = AuthorisationRecord(email.toLowerCase, request.user.fullName, new DateTime())
        Persistence.store.setAuthorisation(auth)
        Redirect(routes.Login.authList())
      }
    )
  }

  def authDelete = AuthAction { implicit request =>
    authorisationForm.bindFromRequest().fold( _ => {}, email => {
      log.info(s"${request.user.fullName} deleted authorisation for $email")
      Persistence.store.deleteAuthorisation(email)
    } )
    Redirect(routes.Login.authList())
  }

}
