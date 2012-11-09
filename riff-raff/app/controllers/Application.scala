package controllers

import play.api.mvc._

import play.api.Logger
import io.Source

trait Logging {
  implicit val log = Logger(getClass)
}

trait MenuItem {
  def title:String
  def target:Call
  def isActive(request:AuthenticatedRequest[AnyContent]):Boolean
  def isVisible(request:AuthenticatedRequest[AnyContent]):Boolean
}

case class SingleMenuItem(title: String, target: Call, identityRequired: Boolean = true, activeInSubPaths: Boolean = false) extends MenuItem{
  def isActive(request: AuthenticatedRequest[AnyContent]): Boolean = {
    activeInSubPaths && request.path.startsWith(target.url) || request.path == target.url
  }
  def isVisible(request: AuthenticatedRequest[AnyContent]): Boolean = !identityRequired || request.identity.isDefined
}

case class DropDownMenuItem(title:String, items: Seq[SingleMenuItem], target: Call = Call("GET", "#")) extends MenuItem {
  def isActive(request: AuthenticatedRequest[AnyContent]) = items.map(_.isActive(request)).fold(false)(_ || _)
  def isVisible(request: AuthenticatedRequest[AnyContent]) = items.map(_.isVisible(request)).fold(false)(_ || _)
}

object Menu {
  lazy val menuItems = Seq(
    SingleMenuItem("Home", routes.Application.index(), identityRequired = false),
    SingleMenuItem("Documentation", routes.Application.documentation(""), identityRequired = false, activeInSubPaths = true),
    SingleMenuItem("Deployment Info", routes.Application.deployInfo(stage = "")),
    SingleMenuItem("Deploy", routes.Deployment.deploy()),
    SingleMenuItem("History", routes.Deployment.history()),
    DropDownMenuItem("Configuration", Seq(
      SingleMenuItem("Continuous Deployment", routes.Deployment.continuousDeployment()),
      SingleMenuItem("Hooks", routes.Hooks.list())
    ))
  )

  lazy val loginMenuItem = SingleMenuItem("Login", routes.Login.loginAction(), identityRequired = false)

  def items(request: AuthenticatedRequest[AnyContent]) = {
    menuItems.filter(_.isVisible(request))
  }
}

object Application extends Controller with Logging {

  def index = NonAuthAction { implicit request =>
    request.identity.isDefined
    Ok(views.html.index(request))
  }

  def deployInfo(stage: String) = AuthAction { request =>
    Ok(views.html.deploy.hostInfo(request))
  }

  def documentation(resource: String) = NonAuthAction { request =>
    try {
      val realResource = if (resource.isEmpty || resource.last == '/') "%sindex" format resource else resource
      log.info("Getting page for %s" format realResource)
      val url = getClass.getResource("/docs/%s.md" format realResource)
      log.info("Resolved URL %s" format url)
      val markDown = Source.fromURL(url).mkString
      Ok(views.html.markdown(request, "Documentation for %s" format realResource, markDown))
    } catch {
      case e:Throwable => NotFound(views.html.notFound(request,"No documentation found for %s" format resource,Some(e)))
    }
  }

}