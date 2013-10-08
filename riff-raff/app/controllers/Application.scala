package controllers

import play.api.mvc._

import play.api.{Routes, Logger}
import io.Source

trait Logging {
  implicit val log = Logger(getClass.getName.stripSuffix("$"))
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
      SingleMenuItem("Continuous Deployment", routes.ContinuousDeployController.list()),
      SingleMenuItem("Hooks", routes.Hooks.list()),
      SingleMenuItem("Authorisation", routes.Login.authList(), enabled = conf.Configuration.auth.whitelist.useDatabase),
      SingleMenuItem("API keys", routes.Api.listKeys())
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
    val url = getClass.getResource("/docs/releases.md")
    val markDown = Source.fromURL(url).mkString
    Ok(views.html.index(request, markDown))
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

  val Prev = """.*prev:(\S+).*""".r
  val Next = """.*next:(\S+).*""".r

  def getMarkdownLines(resource: String):List[String] = {
    val realResource = if (resource.isEmpty || resource.last == '/') s"${resource}index" else resource
    log.info(s"Getting page for $realResource")
    try {
      val url = getClass.getResource(s"/docs/$realResource.md")
      log.info(s"Resolved URL $url")
      Source.fromURL(url).getLines().toList
    } catch {
      case e:Throwable =>
        log.warn(s"$resource is not a valid page of documentation")
        Nil
    }
  }

  case class Link(title:String, call:Call, url:String)
  object Link {
    def apply(url:String):Option[Link] = {
      getMarkdownTitle(getMarkdownLines(url)).map { prevTitle =>
        Link(prevTitle, routes.Application.documentation(url), url)
      }
    }
  }

  def makeAbsolute(resource:String, relative:String): String = {
    if (relative.isEmpty)
      resource
    else if (relative.startsWith("/"))
      relative
    else {
      val base = if (resource.endsWith("/")) resource else resource.split("/").init.mkString("","/","/")
      if (relative.startsWith("../")) {
        makeAbsolute(resource.split("/").init.init.mkString("","/","/"),relative.stripPrefix("../"))
      } else {
        base + relative
      }
    }
  }

  def getNextPrevFromHeader(resource: String, lines: List[String]):(Option[Link], Option[Link]) = {
    val header = lines.head
    val prev = header match {
      case Prev(prevUrl) => Link(makeAbsolute(resource, prevUrl))
      case _ => None
    }
    val next = header match {
      case Next(nextUrl) => Link(makeAbsolute(resource, nextUrl))
      case _ => None
    }
    log.info(s"Header is $header $prev $next")
    (next,prev)
  }

  def getMarkdownTitle(lines: List[String]):Option[String] = {
    lines.find(line => !line.trim.isEmpty && !line.trim.startsWith("<!--"))
  }

  def documentation(resource: String) = {
    if (resource.endsWith(".png")) {
      Assets.at("/docs",resource)
    } else {
      NonAuthAction { request =>
        if (resource.endsWith("/index")) {
          Redirect(routes.Application.documentation(resource.stripSuffix("index")))
        } else {
          val markDownLines = getMarkdownLines(resource)
          if (markDownLines.isEmpty) {
              NotFound(views.html.notFound(request,s"No documentation found for $resource"))
          } else {
            val markDown = markDownLines.mkString("\n")

            val title = getMarkdownTitle(markDownLines).getOrElse(resource)
            val (next,prev) = getNextPrevFromHeader(resource,markDownLines)

            val hierarchy = resource.split('/').filterNot(_.isEmpty).init
            val breadcrumbs = hierarchy.foldLeft(List(Link("Documentation",routes.Application.documentation(""),""))){ (acc, crumb) =>
              acc ++ Link(List(acc.last.url.stripSuffix("/"),crumb).filterNot(_.isEmpty).mkString("","/","/"))
            } ++ Some(Link(title, routes.Application.documentation(resource), resource))

            Ok(views.html.markdown(request, s"Documentation: $title", markDown, breadcrumbs, prev, next))
          }
        }
      }
    }
  }

  def javascriptRoutes = NonAuthAction { implicit request =>
    import routes.javascript._
    Ok{
      Routes.javascriptRouter("jsRoutes")(
        Deployment.stop,
        Deployment.projectHistory,
        Deployment.dashboardContent
      )
    }.as("text/javascript")
  }

}