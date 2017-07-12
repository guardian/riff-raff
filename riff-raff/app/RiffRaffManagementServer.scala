package riffraff

import com.gu.management.ManagementPage
import com.gu.management.internal.{ManagementHandler, ManagementServer}
import lifecycle.Lifecycle
import play.api.Logger

class RiffRaffManagementServer(applicationNameParam: String, pagesParam: List[ManagementPage], log: Logger)
  extends Lifecycle {

  def startServer(): Unit = {
    log.info(s"Starting internal management server for $applicationNameParam")
    ManagementServer.start(new ManagementHandler {
      val applicationName = applicationNameParam
      val pages = pagesParam
    })
  }

  def stop() {
    log.info(s"Shutting down management server")
    ManagementServer.shutdown()
  }

  override def init(): Unit = startServer()
  override def shutdown(): Unit = stop()
}
