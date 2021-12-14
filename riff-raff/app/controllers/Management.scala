package controllers

import deployment.Deployments
import magenta.Switchable
import play.api.mvc.{BaseController, ControllerComponents}

class Management(
  val controllerComponents: ControllerComponents,
  val shutdownSwitch: Switchable,
  val deployments: Deployments,
) extends BaseController with Logging {

  def requestShutdown() = Action { request =>
    shutdownSwitch.switchOn()
    Ok("Shutdown requested.")
  }
}