package controllers

import com.gu.googleauth.UserIdentity
import play.api.mvc._

import play.api.{Play, Routes, Logger}
import io.Source
import magenta.deployment_type.DeploymentType
import magenta.{App, DeploymentPackage}
import java.io.File
import resources.LookupSelector

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
    SingleMenuItem("Documentation", routes.Application.documentation(""), activeInSubPaths = true)
  )

  lazy val deployInfoMenu = Seq(
    SingleMenuItem("Hosts", routes.Application.deployInfoHosts()),
    SingleMenuItem("Resources", routes.Application.deployInfoData()),
    SingleMenuItem("About", routes.Application.deployInfoAbout())
  )

  lazy val loginMenuItem = SingleMenuItem("Login", routes.Login.loginAction(), identityRequired = false)
}

object Application extends Controller with Logging with LoginActions {
  import play.api.Play.current

  def index = Action { implicit request =>
    val stream = Play.resourceAsStream("public/docs/releases.md").get
    val markDown = Source.fromInputStream(stream).mkString
    Ok(views.html.index(request, markDown))
  }

  def deployInfoData = AuthAction { request =>
    Ok(views.html.deploy.deployInfoData(request))
  }

  def deployInfoHosts(appFilter: String) = AuthAction { request =>
    val lookup = LookupSelector()
    val hosts = lookup.hosts.all.filter { host =>
      host.apps.exists(_.toString.matches(s"(?i).*${appFilter}.*")) &&
      request.getQueryString("stack").forall(s => host.stack.exists(_ == s))
    }.groupBy(_.stage)
    Ok(views.html.deploy.deployInfoHosts(request, hosts, lookup))
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
      val url =  Play.resourceAsStream(s"public/docs/$realResource.md").orElse(Play.resourceAsStream(s"$realResource.md")).get
      log.info(s"Resolved URL $url")
      Source.fromInputStream(url).getLines().toList
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
      AuthAction { request =>
        if (resource.endsWith("/index")) {
          Redirect(routes.Application.documentation(resource.stripSuffix("index")))
        } else {
          val markDownLines = getMarkdownLines(resource)
          if (markDownLines.isEmpty) {
              NotFound(views.html.notFound(request,s"No documentation found for $resource",None))
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
                    val defaultValue = (param.defaultValue, param.defaultValueFromPackage) match {
                      case (Some(default), _) => Some(default.toString)
                      case (None, Some(pkgFunction)) =>
                        Some(pkgFunction(
                          DeploymentPackage("<packageName>",Seq(App("<app>")),Map.empty,"<deploymentType>",new File("<file>"))
                        ).toString)
                      case (_, _) => None
                    }
                    (param.name, param.documentation, defaultValue)
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
    import routes.javascript._
    Ok{
      Routes.javascriptRouter("jsRoutes")(
        DeployController.stop,
        DeployController.projectHistory,
        DeployController.dashboardContent,
        DeployController.buildInfo
      )
    }.as("text/javascript")
  }

}