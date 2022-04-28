package controllers

import lifecycle.ShutdownWhenInactive
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

class Management(
  val controllerComponents: ControllerComponents,
  val shutdown: ShutdownWhenInactive,
) extends BaseController with Logging {

  def requestShutdown(): Action[AnyContent] = Action { _ =>
    shutdown.switch.switchOn()
    Ok("Shutdown requested.")
  }
}
