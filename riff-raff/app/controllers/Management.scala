package controllers

import lifecycle.{ShutdownWhenInactive, TerminateInstanceWhenInactive}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

class Management(
    val controllerComponents: ControllerComponents,
    val shutdown: ShutdownWhenInactive,
    val rotateInstance: TerminateInstanceWhenInactive
) extends BaseController
    with Logging {

  def requestShutdown(): Action[AnyContent] = Action { _ =>
    shutdown.switch.switchOn()
    Ok("Shutdown requested.")
  }

  def requestInstanceRotation(): Action[AnyContent] = Action { _ =>
    rotateInstance.switch.switchOn()
    Ok("Instance rotation requested.")
  }
}
