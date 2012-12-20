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
  def isActive(implicit request:AuthenticatedRequest[AnyContent]):Boolean
  def isVisible(implicit request:AuthenticatedRequest[AnyContent]):Boolean
}

case class SingleMenuItem(title: String, target: Call, identityRequired: Boolean = true, activeInSubPaths: Boolean = false, enabled: Boolean = true) extends MenuItem{
  def isActive(implicit request: AuthenticatedRequest[AnyContent]): Boolean = {
    activeInSubPaths && request.path.startsWith(target.url) || request.path == target.url
  }
  def isVisible(implicit request: AuthenticatedRequest[AnyContent]): Boolean = enabled && (!identityRequired || request.identity.isDefined)
}

case class DropDownMenuItem(title:String, items: Seq[SingleMenuItem], target: Call = Call("GET", "#")) extends MenuItem {
  def isActive(implicit request: AuthenticatedRequest[AnyContent]) = items.exists(_.isActive(request))
  def isVisible(implicit request: AuthenticatedRequest[AnyContent]) = items.exists(_.isVisible(request))
}

object Menu {
  lazy val menuItems = Seq(
    SingleMenuItem("Home", routes.Application.index(), identityRequired = false),
    SingleMenuItem("Documentation", routes.Application.documentation(""), identityRequired = false, activeInSubPaths = true),
    DropDownMenuItem("Deployment Info", deployInfoMenu),
    SingleMenuItem("Deploy", routes.Deployment.deploy()),
    SingleMenuItem("History", routes.Deployment.history()),
    DropDownMenuItem("Configuration", Seq(
      SingleMenuItem("Continuous Deployment", routes.Deployment.continuousDeployment()),
      SingleMenuItem("Hooks", routes.Hooks.list()),
      SingleMenuItem("Authorisation", routes.Login.authList(), enabled = conf.Configuration.auth.whitelist.useDatabase)
    ))
  )

  lazy val deployInfoMenu = Seq(
    SingleMenuItem("Hosts", routes.Application.deployInfoHosts()),
    SingleMenuItem("Resources", routes.Application.deployInfoData()),
    SingleMenuItem("About", routes.Application.deployInfoAbout())
  )

  lazy val loginMenuItem = SingleMenuItem("Login", routes.Login.loginAction(), identityRequired = false)
}

object Application extends Controller with Logging {

  def index = NonAuthAction { implicit request =>
    request.identity.isDefined
    Ok(views.html.index(request))
  }

  def deployInfoData = AuthAction { request =>
    Ok(views.html.deploy.deployInfoData(request))
  }

  def deployInfoHosts(appFilter: String) = AuthAction { request =>
    Ok(views.html.deploy.deployInfoHosts(request, "(?i).*%s.*" format appFilter))
  }

  def deployInfoAbout = AuthAction { request =>
    Ok(views.html.deploy.deployInfoAbout(request))
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