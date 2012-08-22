package controllers

import play.api.mvc._

import play.api.Logger
import conf.TimedAction
import io.Source

trait Logging {
  implicit val log = Logger(getClass)
}

case class MenuItem(title: String, target: Call, identityRequired: Boolean = true) {
  def isActive(request: AuthenticatedRequest[AnyContent]) = target.url == request.path
}

object Menu {
  lazy val menuItems = Seq(
    MenuItem("Home", routes.Application.index, false),
    MenuItem("Deployment Info", routes.Application.deployInfo(stage = "")),
    MenuItem("Deploy Anything\u2122", routes.Deployment.deploy()),
    MenuItem("Deploy History", routes.Deployment.history()),
    MenuItem("Continuous Deployment", routes.Deployment.continuousDeployment())
  )

  lazy val loginMenuItem = MenuItem("Login", routes.Login.login, false)

  def items(request: AuthenticatedRequest[AnyContent]) = {
    val loggedIn = request.identity.isDefined
    menuItems.filter { item =>
      !item.identityRequired ||
        (item.identityRequired && loggedIn)
    }
  }
}

object Application extends Controller with Logging {

  def index = TimedAction {
    NonAuthAction { implicit request =>
      request.identity.isDefined
      Ok(views.html.index(request))
    }
  }

  def deployInfo(stage: String) = TimedAction {
    AuthAction { request =>
      Ok(views.html.deploy.hostInfo(request))
    }
  }

  def documentation(resource: String) = TimedAction {
    AuthAction { request =>
      try {
        val markDown = Source.fromURL(getClass.getResource("docs/%s.md" format resource)).mkString
        Ok(views.html.markdown(request, "Documentation for %s" format resource, markDown))
      } catch {
        case e => NotFound("No documentation found for %s" format resource)
      }
    }
  }

}