package controllers

import play.api.data._
import play.api.data.Forms._

import play.api.mvc._
import deployment._

import play.api.Logger
import conf.{TimedAction, Configuration}
import magenta._
import collection.mutable.ArrayBuffer
import tasks.Task

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
    MenuItem("Frontend-Article CODE", routes.Application.frontendArticleCode(), true),
    MenuItem("Deploy Anything\u2122", routes.Application.deploy(), true)
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
      val stageAppHosts = DeployInfo.hostList filter { host =>
        host.stage == stage || stage == ""
      } groupBy { _.stage } mapValues { hostList =>
        hostList.groupBy {
          _.apps
        }
      }

      Ok(views.html.deployinfo(request, stageAppHosts))
    }
  }

  def profile = TimedAction {
    AuthAction { request =>
      Ok(views.html.profile(request))
    }
  }

  lazy val deployForm = Form[DeployParameterForm](
    mapping(
      "project" -> nonEmptyText,
      "build" -> nonEmptyText,
      "stage" -> nonEmptyText
    )(DeployParameterForm.apply)(DeployParameterForm.unapply)
  )

  def frontendArticleCode = TimedAction {
    AuthAction { request =>
      val parameters = DeployParameterForm("frontend::article","","CODE")
      Ok(views.html.frontendarticle(request, deployForm.fill(parameters)))
    }
  }

  def deploy = TimedAction {
    AuthAction { implicit request =>
      Ok(views.html.deployForm(request, deployForm))
    }
  }

  def doDeploy = TimedAction {
    AuthAction { implicit request =>
      deployForm.bindFromRequest().fold(
        errors => BadRequest(views.html.deployForm(request,errors)),
        form => {
          log.info("Form submitted")
          val deployActor = DeployActor(form.project, Stage(form.stage))
          val s3Creds = S3Credentials(Configuration.s3.accessKey,Configuration.s3.secretAccessKey)
          val keyRing = KeyRing(SystemUser(keyFile = Some(Configuration.sshKey.file)), List(s3Creds))

          val context = new DeployParameters(Deployer(request.identity.get.fullName),
            Build(form.project,form.build.toString),
            Stage(form.stage))

          val key = DeploymentKey(form.stage,form.project,form.build)
          MessageBus.clear(key)

          import deployment.DeployActor.Deploy
          deployActor ! Deploy(context, keyRing)

          Redirect(routes.Application.deployLog(key.stage,key.project,key.build))
        }
      )
    }
  }

  def deployLog(stage: String, project: String, build: String, recipe: String = "default", verbose:Boolean) = TimedAction {
    AuthAction { implicit request =>
      val key = DeploymentKey(stage,project,build,recipe)
      val report = MessageBus.deployReport(key)

      Ok(views.html.deployLog(request, key, report,verbose))
    }
  }

  def deployLogContent(stage: String, project: String, build: String, recipe: String, verbose: Boolean) = TimedAction {
    AuthAction { implicit request =>
      val key = DeploymentKey(stage,project,build,recipe)
      val report = MessageBus.deployReport(key)

      Ok(views.html.snippets.deployLogContent(request,report,verbose))
    }
  }

}