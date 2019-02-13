package controllers

import cats.data.EitherT
import com.gu.googleauth._
import com.mongodb.casbah.commons.MongoDBObject
import conf.Config
import deployment.{DeployFilter, Deployments, Record}
import org.joda.time.DateTime
import persistence.{MongoFormat, MongoSerialisable, Persistence}
import persistence.{DataStore, MongoFormat, MongoSerialisable}
import play.api.data.Forms._
import play.api.data._
import play.api.i18n.I18nSupport
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.mvc._
import scalikejdbc.WrappedResultSet
import utils.Json._
import utils.LogAndSquashBehaviour

import scala.concurrent.{ExecutionContext, Future}

class ApiRequest[A](val apiKey: ApiKey, request: Request[A]) extends WrappedRequest[A](request) {
  lazy val fullName = s"API:${apiKey.application}"
}

case class AuthorisationRecord(email: String, approvedBy: String, approvedDate: DateTime) {
  def contentBlob = (approvedBy, approvedDate)
}
object AuthorisationRecord extends MongoSerialisable[AuthorisationRecord] {
  implicit val formats: Format[AuthorisationRecord] = Json.format[AuthorisationRecord]

  def apply(res: WrappedResultSet): AuthorisationRecord = Json.parse(res.string(1)).as[AuthorisationRecord]

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
    if (emailDomainWhitelist.nonEmpty && !emailDomainWhitelist.contains(id.emailDomain)) {
      Some(s"The e-mail address domain you used to login to Riff-Raff (${id.email}) is not in the configured whitelist.  Please try again with another account or contact the Riff-Raff administrator.")
    } else if (emailWhitelistEnabled && !emailWhitelistContains(id.email)) {
      Some(s"The e-mail address you used to login to Riff-Raff (${id.email}) is not authorised.  Please try again with another account, ask a colleague to add your address or contact the Riff-Raff administrator.")
    } else {
      None
    }
  }
}

class Login(config: Config, menu: Menu, deployments: Deployments, datastore: DataStore, val controllerComponents: ControllerComponents, val authAction: AuthAction[AnyContent], val authConfig: GoogleAuthConfig)
  (implicit val wsClient: WSClient, val executionContext: ExecutionContext)
  extends BaseController with Logging with LoginSupport with I18nSupport with LogAndSquashBehaviour {

  val validator = new AuthorisationValidator {
    def emailDomainWhitelist = config.auth.domains
    def emailWhitelistEnabled = config.auth.whitelist.useDatabase || config.auth.whitelist.addresses.nonEmpty
    def emailWhitelistContains(email: String) = {
      val lowerCaseEmail = email.toLowerCase
      config.auth.whitelist.addresses.contains(lowerCaseEmail) ||
        (config.auth.whitelist.useDatabase && datastore.getAuthorisation(lowerCaseEmail).exists(_.isDefined))
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
        else Left(redirectWithError(
          failureRedirectTarget,  validator.authorisationError(identity).getOrElse("Unknown error")))
      }
    } yield {
      setupSessionWhenSuccessful(identity)
    }).merge
  }

  def logout = Action { implicit request =>
    Redirect("/").withNewSession
  }

  def profile = authAction { request =>
    val records = deployments.getDeploys(Some(DeployFilter(deployer=Some(request.user.fullName)))).map(_.reverse)
    records.fold(
      (t: Throwable) => InternalServerError(views.html.errorContent(t, "Could not fetch list of deploys")(config)),
      (as: List[Record]) => Ok(views.html.auth.profile(config, menu)(request, as))
    )
  }

  val authorisationForm = Form( "email" -> nonEmptyText )

  def authList = authAction { request =>
    datastore.getAuthorisationList.map(_.sortBy(_.email)).fold(
      (t: Throwable) => InternalServerError(views.html.errorContent(t, "Could not fetch authorisation list")(config)),
      (as: Seq[AuthorisationRecord]) => Ok(views.html.auth.list(config, menu)(request, as))
    )
  }

  def authForm = authAction { implicit request =>
    Ok(views.html.auth.form(config, menu)(authorisationForm))
  }

  def authSave = authAction { implicit request =>
    authorisationForm.bindFromRequest().fold(
      errors => BadRequest(views.html.auth.form(config, menu)(errors)),
      email => {
        val auth = AuthorisationRecord(email.toLowerCase, request.user.fullName, new DateTime())
        datastore.setAuthorisation(auth)
        Redirect(routes.Login.authList())
      }
    )
  }

  def authDelete = authAction { implicit request =>
    authorisationForm.bindFromRequest().fold( _ => {}, email => {
      log.info(s"${request.user.fullName} deleted authorisation for $email")
      datastore.deleteAuthorisation(email)
    } )
    Redirect(routes.Login.authList())
  }

  override val failureRedirectTarget: Call = routes.Login.login()
  override val defaultRedirectTarget: Call = routes.Application.index()
}
