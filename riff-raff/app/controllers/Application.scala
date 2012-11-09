package controllers

import play.api.mvc._

import play.api.Logger
import io.Source

trait Logging {
  implicit val log = Logger(getClass)
}

case class MenuItem(title: String, target: Call, nestedItems: Seq[MenuItem] = Nil, identityRequired: Boolean = true, activeInSubPaths: Boolean = false) {
  def isActive(request: AuthenticatedRequest[AnyContent]): Boolean = {
    nestedItems.map(_.isActive(request)).fold(false)(_ || _) ||
    activeInSubPaths && request.path.startsWith(target.url) ||
    request.path == target.url
  }
}

object Menu {
  lazy val menuItems = Seq(
    MenuItem("Home", routes.Application.index(), identityRequired = false),
    MenuItem("Documentation", routes.Application.documentation(""), identityRequired = false, activeInSubPaths = true),
    MenuItem("Deployment Info", routes.Application.deployInfo(stage = "")),
    MenuItem("Deploy", routes.Deployment.deploy()),
    MenuItem("History", routes.Deployment.history()),
    MenuItem("Configuration", Call("GET","#"), Seq(
      MenuItem("Continuous Deployment", routes.Deployment.continuousDeployment()),
      MenuItem("Hooks", routes.Hooks.list())
    ))
  )

  lazy val loginMenuItem = MenuItem("Login", routes.Login.loginAction(), identityRequired = false)

  def items(request: AuthenticatedRequest[AnyContent]) = {
    val loggedIn = request.identity.isDefined
    menuItems.filter { item =>
      !item.identityRequired ||
        (item.identityRequired && loggedIn)
    }
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