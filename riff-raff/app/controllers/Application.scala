package controllers

import play.api._
import libs.concurrent.{ Promise, Akka }
import play.api.Play.current
import play.api.data._
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.mvc._
import deployment._
import java.io.File
import magenta.json.{ JsonReader, DeployInfoJsonReader }
import magenta.{ Stage, Resolver }
import magenta.tasks.Task

trait Logging {
  implicit val log = Logger(getClass)
}

case class MenuItem(title: String, target: Call, identityRequired: Boolean) {
  def isActive(request: AuthenticatedRequest[AnyContent]) = target.url == request.path
}

object Menu {
  lazy val menuItems = Seq(
    MenuItem("Home", routes.Application.index, false),
    MenuItem("Deployment Info", routes.Application.deployInfo(stage = ""), true),
    MenuItem("Frontend-Article CODE", routes.Application.frontendArticleCode(), true)
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

  def index = NonAuthAction { implicit request =>
    request.identity.isDefined
    Ok(views.html.index(request))
  }

  lazy val parsedDeployInfo = {
    import sys.process._
    DeployInfoJsonReader.parse("/opt/bin/deployinfo.json".!!)
  }

  def deployInfo(stage: String) = AuthAction { request =>
    val stageAppHosts = parsedDeployInfo filter { host =>
      host.stage == stage || stage == ""
    } groupBy { _.stage } mapValues { hostList =>
      hostList.groupBy {
        _.apps
      }
    }

    Ok(views.html.deployinfo(request, stageAppHosts))
  }

  def profile = AuthAction { request =>
    Ok(views.html.profile(request))
  }

  lazy val deployForm = Form(
    "build" -> number(min = 1)
  )

  def frontendArticleCode = AuthAction { request =>
    Ok(views.html.frontendarticle(request, deployForm))
  }

  def deployFrontendArticleCode = AuthAction { implicit request =>
    val stage = "CODE"
    val recipe = "default"
    val deploy = deployForm.bindFromRequest()

    val promiseOfTasks: Promise[List[Task]] = Akka.future {
      log.info("Downloading artifact")
      val artifactDir = Artifact.download("frontend::article", deploy.get)
      log.info("Reading deploy.json")
      val project = JsonReader.parse(new File(artifactDir, "deploy.json"))
      val hosts = parsedDeployInfo.filter(_.stage == stage)
      log.info("Resolving tasks")
      val tasks = Resolver.resolve(project, recipe, hosts, Stage(stage))
      tasks
    }
    Async {
      promiseOfTasks.map(tasks => Ok(views.html.deployfrontendarticle(request, tasks)))
    }
  }

}