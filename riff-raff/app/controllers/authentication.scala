package controllers

import play.api.mvc._
import play.api.mvc.Results._
import play.api.mvc.BodyParsers._
import net.liftweb.json.{ Serialization, NoTypeHints }
import net.liftweb.json.Serialization.{ read, write }
import play.api.libs.openid.OpenID
import play.api.libs.concurrent.{ Thrown, Redeemed }
import conf._

case class Identity(openid: String, email: String, firstName: String, lastName: String) {
  implicit val formats = Serialization.formats(NoTypeHints)
  def writeJson = write(this)

  lazy val fullName = firstName + " " + lastName
  lazy val emailDomain = email.split("@").last
}

object Identity {
  implicit val formats = Serialization.formats(NoTypeHints)
  def readJson(json: String) = read[Identity](json)
}

class AuthenticatedRequest[A](val identity: Option[Identity], request: Request[A]) extends WrappedRequest(request) {
  lazy val isAuthenticated = identity.isDefined
}

object NonAuthAction {

  def apply[A](p: BodyParser[A])(f: AuthenticatedRequest[A] => Result) = {
    Action(p) { implicit request =>
      val identity = request.session.get("identity").map(credentials => Identity.readJson(credentials))
      f(new AuthenticatedRequest(identity, request))
    }
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
      request.session.get("identity").map(credentials => Identity.readJson(credentials)).map { identity =>
        f(new AuthenticatedRequest(Some(identity), request))
      }.getOrElse(Redirect(routes.Login.login).withSession {
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
  val openIdAttributes = Seq(
    ("email", "http://axschema.org/contact/email"),
    ("firstname", "http://axschema.org/namePerson/first"),
    ("lastname", "http://axschema.org/namePerson/last")
  )
  val googleOpenIdUrl = "https://www.google.com/accounts/o8/id"

  def login = NonAuthAction { request =>
    val error = request.flash.get("error")
    Ok(views.html.auth.login(request, error))
  }

  def loginPost = Action { implicit request =>
    AsyncResult(
      OpenID
        .redirectURL(googleOpenIdUrl, routes.Login.openIDCallback.absoluteURL(), openIdAttributes)
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
          if (credentials.emailDomain == "guardian.co.uk") {
            Redirect(session.get("loginFromUrl").getOrElse("/")).withSession {
              session + ("identity" -> credentials.writeJson) - "loginFromUrl"
            }
          } else {
            FailedLoginCounter.recordCount(1)
            Redirect(routes.Login.login).flashing(
              ("error" -> "I'm afraid you can only log into Riff-Raff using your Guardian Google Account")
            ).withSession(session - "identity")
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
    Redirect("/").withSession {
      session - "identity"
    }
  }

  def profile = TimedAction {
    AuthAction { request =>
      val deployerRecords = DeployLibrary.get.filter { record =>
        request.identity.map(_.fullName == record.deployerName).getOrElse(false)
      }.reverse
      Ok(views.html.auth.profile(request, deployerRecords))
    }
  }
}
