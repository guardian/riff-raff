package controllers

import lifecycle.{ShutdownWhenInactive, TerminateInstanceWhenInactive}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}

import java.net.InetAddress

class Management(
    val controllerComponents: ControllerComponents,
    val shutdown: ShutdownWhenInactive,
    val rotateInstance: TerminateInstanceWhenInactive
) extends BaseController
    with Logging {

  def requestShutdown(): Action[AnyContent] = Action { request =>
    if (!InetAddress.getByName(request.remoteAddress).isLoopbackAddress) {
      Forbidden(
        s"${request.path} restricted to ${InetAddress.getLoopbackAddress}"
      )
    } else {
      shutdown.switch.switchOn()
      Ok("Shutdown requested.")
    }
  }

  def requestInstanceRotation(): Action[AnyContent] = Action { request =>
    if (!InetAddress.getByName(request.remoteAddress).isLoopbackAddress) {
      Forbidden(
        s"${request.path} restricted to ${InetAddress.getLoopbackAddress}"
      )
    } else {
      rotateInstance.switch.switchOn()
      Ok("Instance rotation requested.")
    }
  }
}
