package controllers

import play.api._
import libs._
import libs.iteratee._
import libs.concurrent._

import play.api.data._
import play.api.data.Forms._

import play.api.mvc._
import deployment._

import magenta.json.{ JsonReader, DeployInfoJsonReader }
import magenta.{ Stage, Resolver }

import deployment.DeployActor.Deploy
import akka.util.duration._
import akka.util.Timeout
import akka.pattern.ask
import deployment.MessageBus.Watch

import conf._

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

  def index = TimedAction {
    NonAuthAction { implicit request =>
      request.identity.isDefined
      Ok(views.html.index(request))
    }
  }

  def deployInfo(stage: String) = TimedAction {
    AuthAction { request =>
      val stageAppHosts = DeployInfo.parsedDeployInfo filter { host =>
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

  lazy val deployForm = Form(
    "build" -> number(min = 1)
  )

  def frontendArticleCode = TimedAction {
    AuthAction { request =>
      Ok(views.html.frontendarticle(request, deployForm))
    }
  }

  def deployFrontendArticleCode = TimedAction {
    AuthAction { implicit request =>
      val stage = "CODE"
      val build = deployForm.bindFromRequest().get

      val deployActor = DeployActor("frontend-article", Stage(stage))
      val updateActor = MessageBus(deployActor)

      deployActor ! Deploy(build, updateActor)

      Ok(views.html.deployfrontendarticle(request, updateActor.path.toString))
    }
  }

  def deployFrontendArticleCodeComet(updateActorPath: String) = TimedCometAction {
    AuthAction { implicit request =>
      val updateActor = MessageBus.system.actorFor(updateActorPath)
      // updateActor register to listen

      // send deploy initialising message
      // deployActor ! Deploy(build, updateActor)
      //updateActor !

      AsyncResult {
        implicit val timeout = Timeout(5.seconds)
        (updateActor ? (Watch())).mapTo[Enumerator[String]].asPromise.map { chunks =>
          log.info("streaming new chunk")
          Ok.stream(chunks &> Comet(callback = "parent.appendOutput"))
        }
      }
    }
  }

}