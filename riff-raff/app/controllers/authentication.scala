package controllers

import play.api.mvc._
import play.api.mvc.Results._
import play.api.mvc.BodyParsers._
import net.liftweb.json.{ Serialization, NoTypeHints }
import net.liftweb.json.Serialization.{ read, write }
import play.api.libs.openid.OpenID
import play.api.libs.concurrent.{ Thrown, Redeemed }
import conf._
import conf.Configuration.auth
import persistence.{MongoSerialisable, Persistence}
import org.joda.time.DateTime
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.Imports._
import com.mongodb.DBObject
import play.api.data._
import play.api.data.Forms._


case class Identity(openid: String, email: String, firstName: String, lastName: String) {
  implicit val formats = Serialization.formats(NoTypeHints)
  def writeJson = write(this)

  lazy val fullName = firstName + " " + lastName
  lazy val emailDomain = email.split("@").last
}

object Identity {
  val KEY = "identity"
  implicit val formats = Serialization.formats(NoTypeHints)
  def readJson(json: String) = read[Identity](json)
  def apply(request: Request[Any]): Option[Identity] = {
    request.session.get(KEY).map(credentials => Identity.readJson(credentials))
  }
}

case class AuthorisationRecord(email: String, approvedBy: String, approvedDate: DateTime) extends MongoSerialisable {
  def dbObject = MongoDBObject("_id" -> email, "approvedBy" -> approvedBy, "approvedDate" -> approvedDate)
}
object AuthorisationRecord {
  def apply(dbo: DBObject): AuthorisationRecord = AuthorisationRecord(dbo.as[String]("_id"), dbo.as[String]("approvedBy"), dbo.as[DateTime]("approvedDate"))
}

trait AuthorisationValidator {
  def emailDomainWhitelist: List[String]
  def emailWhitelistEnabled: Boolean
  def emailWhitelistContains(email:String): Boolean
  def isAuthorised(id: Identity) = authorisationError(id).isEmpty
  def authorisationError(id: Identity): Option[String] = {
    if (!emailDomainWhitelist.isEmpty && !emailDomainWhitelist.contains(id.emailDomain)) {
      Some("The e-mail address domain you used to login to Riff-Raff (%s) is not in the configured whitelist.  Please try again with another account or contact the Riff-Raff administrator." format id.email)
    } else if (emailWhitelistEnabled && !emailWhitelistContains(id.email)) {
      Some("The e-mail address you used to login to Riff-Raff (%s) is not authorised.  Please try again with another account, ask a colleague to add your address or contact the Riff-Raff administrator." format id.email)
    } else {
      None
    }
  }
}

object AuthenticatedRequest {
  def apply[A](request: Request[A]) = {
    new AuthenticatedRequest(Identity(request), request)
  }
}

class AuthenticatedRequest[A](val identity: Option[Identity], request: Request[A]) extends WrappedRequest(request) {
  lazy val isAuthenticated = identity.isDefined
}

object NonAuthAction {

  def apply[A](p: BodyParser[A])(f: AuthenticatedRequest[A] => Result) = {
    Action(p) { implicit request => f(AuthenticatedRequest(request)) }
  }

  def apply(f: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] = {
    this.apply(parse.anyContent)(f)
  }

  def apply(block: => Result): Action[AnyContent] = {
    this.apply(_ => block)
  }

}

object AuthAction {

  def apply[A](p: BodyParser[A])(f: AuthenticatedRequest[A] => Result) = {
    Action(p) { implicit request =>
      Identity(request).map { identity =>
        f(new AuthenticatedRequest(Some(identity), request))
      }.getOrElse(Redirect(routes.Login.loginAction).withSession {
        request.session + ("loginFromUrl", request.uri)
      })
    }
  }

  def apply(f: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] = {
    this.apply(parse.anyContent)(f)
  }

  def apply(block: => Result): Action[AnyContent] = {
    this.apply(_ => block)
  }

}

object Login extends Controller with Logging {
  val validator = new AuthorisationValidator {
    def emailDomainWhitelist = auth.domains
    def emailWhitelistEnabled = auth.whitelist.useDatabase || !auth.whitelist.addresses.isEmpty
    def emailWhitelistContains(email: String) = {
      val lowerCaseEmail = email.toLowerCase
      auth.whitelist.addresses.contains(lowerCaseEmail) ||
        (auth.whitelist.useDatabase && Persistence.store.getAuthorisation(lowerCaseEmail).isDefined)
    }
  }

  val openIdAttributes = Seq(
    ("email", "http://axschema.org/contact/email"),
    ("firstname", "http://axschema.org/namePerson/first"),
    ("lastname", "http://axschema.org/namePerson/last")
  )

  def login = NonAuthAction { request =>
    val error = request.flash.get("error")
    Ok(views.html.auth.login(request, error))
  }

  def loginAction = Action { implicit request =>
    AsyncResult(
      OpenID
        .redirectURL(auth.openIdUrl, routes.Login.openIDCallback.absoluteURL(), openIdAttributes)
        .extend(_.value match {
          case Redeemed(url) => {
            LoginCounter.recordCount(1)
            Redirect(url)
          }
          case Thrown(t) => Redirect(routes.Login.login).flashing(("error" -> "Unknown error: %s ".format(t.getMessage)))
        })
    )
  }

  def openIDCallback = Action { implicit request =>
    AsyncResult(
      OpenID.verifiedId.extend(_.value match {
        case Redeemed(info) => {
          val credentials = Identity(
            info.id,
            info.attributes.get("email").get,
            info.attributes.get("firstname").get,
            info.attributes.get("lastname").get
          )
          if (validator.isAuthorised(credentials)) {
            Redirect(session.get("loginFromUrl").getOrElse("/")).withSession {
              session + (Identity.KEY -> credentials.writeJson) - "loginFromUrl"
            }
          } else {
            FailedLoginCounter.recordCount(1)
            Redirect(routes.Login.login).flashing(
              ("error" -> (validator.authorisationError(credentials).get))
            ).withSession(session - Identity.KEY)
          }
        }
        case Thrown(t) => {
          // Here you should look at the error, and give feedback to the user
          FailedLoginCounter.recordCount(1)
          Redirect(routes.Login.login)
        }
      })
    )
  }

  def logout = Action { implicit request =>
    Redirect("/").withNewSession
  }

  def profile = AuthAction { request =>
    val deployerRecords = DeployController.getDeploys(fetchLogs=false).filter { record =>
      request.identity.map(_.fullName == record.deployerName).getOrElse(false)
    }.reverse
    Ok(views.html.auth.profile(request, deployerRecords))
  }

  val authorisationForm = Form( "email" -> nonEmptyText )

  def authList = AuthAction { request =>
    Ok(views.html.auth.list(request, Persistence.store.getAuthorisationList.sortBy(_.email)))
  }

  def authForm = AuthAction { request =>
    Ok(views.html.auth.form(request, authorisationForm))
  }

  def authSave = AuthAction { implicit request =>
    authorisationForm.bindFromRequest().fold(
      errors => BadRequest(views.html.auth.form(request, errors)),
      email => {
        val auth = AuthorisationRecord(email.toLowerCase, request.identity.get.fullName, new DateTime())
        Persistence.store.setAuthorisation(auth)
        Redirect(routes.Login.authList())
      }
    )
  }

  def authDelete = AuthAction { implicit request =>
    authorisationForm.bindFromRequest().fold( _ => {}, email => {
      log.info("%s deleted authorisation for %s" format (request.identity.get.fullName, email))
      Persistence.store.deleteAuthorisation(email)
    } )
    Redirect(routes.Login.authList())
  }

}
