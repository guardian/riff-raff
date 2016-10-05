package controllers

import com.gu.googleauth.UserIdentity
import play.api.mvc._
import play.api.{Environment, Logger}
import magenta.deployment_type.DeploymentType
import magenta.{App, DeploymentPackage}
import magenta.withResource
import magenta.artifact.S3Path
import play.api.libs.ws.WSClient
import resources.PrismLookup

import scala.io.Source

trait Logging {
  implicit val log = Logger(getClass.getName.stripSuffix("$"))
}

trait MenuItem {
  def title:String
  def target:Call
  def isActive(implicit request:Request[AnyContent]):Boolean
  def isVisible(hasIdentity:Boolean):Boolean
  def isVisible(implicit request:Request[AnyContent]):Boolean = isVisible(UserIdentity.fromRequest(request).isDefined)
}

case class SingleMenuItem(title: String, target: Call, identityRequired: Boolean = true, activeInSubPaths: Boolean = false, enabled: Boolean = true) extends MenuItem{
  def isActive(implicit request: Request[AnyContent]): Boolean = {
    activeInSubPaths && request.path.startsWith(target.url) || request.path == target.url
  }
  def isVisible(hasIdentity:Boolean = false) = enabled && (hasIdentity || !identityRequired)
}

case class DropDownMenuItem(title:String, items: Seq[SingleMenuItem], target: Call = Call("GET", "#")) extends MenuItem {
  def isActive(implicit request: Request[AnyContent]) = items.exists(_.isActive(request))
  def isVisible(hasIdentity:Boolean = false) = items.exists(_.isVisible(hasIdentity))
}

object Menu {
  lazy val menuItems = Seq(
    SingleMenuItem("Home", routes.Application.index(), identityRequired = false),
    SingleMenuItem("History", routes.DeployController.history()),
    SingleMenuItem("Deploy", routes.DeployController.deploy()),
    DropDownMenuItem("Deployment Info", deployInfoMenu),
    DropDownMenuItem("Configuration", Seq(
      SingleMenuItem("Continuous Deployment", routes.ContinuousDeployController.list()),
      SingleMenuItem("Hooks", routes.Hooks.list()),
      SingleMenuItem("Authorisation", routes.Login.authList(), enabled = conf.Configuration.auth.whitelist.useDatabase),
      SingleMenuItem("API keys", routes.Api.listKeys())
    )),
    DropDownMenuItem("Documentation", Seq(
      SingleMenuItem("Home", routes.Application.documentation("")),
      SingleMenuItem("Deployment Types", routes.Application.documentation("magenta-lib/types"))
    ))
  )

  lazy val deployInfoMenu = Seq(
    SingleMenuItem("Hosts", routes.Application.deployInfoHosts()),
    SingleMenuItem("Resources", routes.Application.deployInfoData()),
    SingleMenuItem("About", routes.Application.deployInfoAbout())
  )

  lazy val loginMenuItem = SingleMenuItem("Login", routes.Login.loginAction(), identityRequired = false)
}

class Application(prismLookup: PrismLookup)(implicit environment: Environment, val wsClient: WSClient) extends Controller with Logging with LoginActions {
  import Application._

  def index = Action { implicit request =>
    val markDown = withResource(environment.resourceAsStream("public/docs/releases.md").get) { stream =>
      Source.fromInputStream(stream).mkString
    }
    Ok(views.html.index(request, markDown))
  }

  def deployInfoData = AuthAction { implicit request =>
    Ok(views.html.deploy.deployInfoData(prismLookup))
  }

  def deployInfoHosts(appFilter: String) = AuthAction { implicit request =>
    val hosts = prismLookup.hosts.all.filter { host =>
      host.apps.exists(_.toString.matches(s"(?i).*${appFilter}.*")) &&
      request.getQueryString("stack").forall(s => host.stack.exists(_ == s))
    }.groupBy(_.stage)
    Ok(views.html.deploy.deployInfoHosts(request, hosts, prismLookup))
  }

  def deployInfoAbout = AuthAction { request =>
    Ok(views.html.deploy.deployInfoAbout(request))
  }

  val Prev = """.*prev:(\S+).*""".r
  val Next = """.*next:(\S+).*""".r

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

  def documentation(resource: String) = {
    if (resource.endsWith(".png")) {
      Assets.at("/docs",resource)
    } else {
      AuthAction { request =>
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

            val breadcrumbs = {
              val hierarchy = resource.split('/').filterNot(_.isEmpty).dropRight(1)
              hierarchy.foldLeft(List(Link("Documentation",routes.Application.documentation(""),""))){ (acc, crumb) =>
                acc ++ Link(List(acc.last.url.stripSuffix("/"),crumb).filterNot(_.isEmpty).mkString("","/","/"))
              } ++ Some(Link(title, routes.Application.documentation(resource), resource))
            }

            resource match {
              case "magenta-lib/types" =>
                val sections = DeploymentType.all.sortBy(_.name).map{ dt =>
                  val paramDocs = dt.params.sortBy(_.name).map{ param =>
                    val defaultLegacy = (param.defaultValue, param.defaultValueFromPackage) match {
                      case (Some(default), _) => Some(default.toString)
                      case (None, Some(pkgFunction)) =>
                        Some(pkgFunction(
                          DeploymentPackage("<packageName>",Seq(App("<app>")),Map.empty,"<deploymentType>", S3Path("<bucket>", "<prefix>"), legacyConfig = true)
                        ).toString)
                      case (_, _) => None
                    }
                    val defaultNew = (param.defaultValue, param.defaultValueFromPackage) match {
                      case (Some(default), _) => Some(default.toString)
                      case (None, Some(pkgFunction)) =>
                        Some(pkgFunction(
                          DeploymentPackage("<packageName>",Seq(App("<app>")),Map.empty,"<deploymentType>", S3Path("<bucket>", "<prefix>"), legacyConfig = false)
                        ).toString)
                      case (_, _) => None
                    }
                    (param.name, param.documentation, defaultLegacy, defaultNew)
                  }.sortBy(_._3.isDefined)
                  val typeDocumentation = views.html.documentation.deploymentTypeSnippet(dt.documentation, paramDocs)
                  (dt.name, typeDocumentation)
                }
                Ok(views.html.documentation.markdownBlocks(request, "Deployment Types", breadcrumbs, sections))
              case _ =>
                Ok(views.html.documentation.markdown(request, s"Documentation: $title", markDown, breadcrumbs, prev, next))
            }
          }
        }
      }
    }
  }

  def javascriptRoutes = Action { implicit request =>
    import play.api.routing._
    Ok{
      JavaScriptReverseRouter("jsRoutes")(
        routes.javascript.DeployController.stop,
        routes.javascript.DeployController.projectHistory,
        routes.javascript.DeployController.dashboardContent,
        routes.javascript.DeployController.buildInfo
      )
    }.as("text/javascript")
  }

}

object Application extends Logging {

  case class Link(title:String, call:Call, url:String)
  object Link {
    def apply(url:String)(implicit environment: Environment):Option[Link] = {
      getMarkdownTitle(getMarkdownLines(url)).map { prevTitle =>
        Link(prevTitle, routes.Application.documentation(url), url)
      }
    }

  }

  def getMarkdownTitle(lines: List[String]):Option[String] = {
    lines.find(line => !line.trim.isEmpty && !line.trim.startsWith("<!--"))
  }

  def getMarkdownLines(resource: String)(implicit environment: Environment): List[String] = {
    val realResource = if (resource.isEmpty || resource.last == '/') s"${resource}index" else resource
    log.info(s"Getting page for $realResource")
    try {
      withResource(environment.resourceAsStream(s"public/docs/$realResource.md").orElse(environment.resourceAsStream(s"$realResource.md")).get) { url =>
        log.info(s"Resolved URL $url")
        Source.fromInputStream(url).getLines().toList
      }
    } catch {
      case e:Throwable =>
        log.warn(s"$resource is not a valid page of documentation")
        Nil
    }
  }


}