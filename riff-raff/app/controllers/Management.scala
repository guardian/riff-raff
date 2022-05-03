package controllers

import lifecycle.{ShutdownWhenInactive, TerminateInstanceWhenInactive}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

class Management(
  val controllerComponents: ControllerComponents,
  val shutdown: ShutdownWhenInactive,
  val terminateInstance: TerminateInstanceWhenInactive
) extends BaseController with Logging {

  def requestShutdown(): Action[AnyContent] = Action { _ =>
    shutdown.switch.switchOn()
    Ok("Shutdown requested.")
  }

  def requestInstanceTermination(): Action[AnyContent] = Action { _ =>
    terminateInstance.switch.switchOn()
    Ok("Instance termination requested.")
  }
}
