package lifecycle

import java.util.UUID

import com.gu.management.DefaultSwitch
import controllers.Logging
import deployment.{DeploySink, DeployManager}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._

object ShutdownWhenInactive extends LifecycleWithoutApp with Logging {
  val EXITCODE = 217

  // switch to enable this mode
  lazy val switch = new DefaultSwitch("shutdown-when-inactive", s"Shutdown riff-raff when there are no running deploys. Turning this on wil cause RiffRaff to exit with exitcode $EXITCODE as soon as the last queued deploy finishes.", false) {
    override def switchOn() = {
      super.switchOn()
      // try and shutdown immediately
      attemptShutdown()
    }
  }

  def attemptShutdown() {
    future {
      log.info("Attempting to shutdown: trying to atomically disable deployment")
      if (DeployManager.atomicDisableDeploys) {
        log.info("Deployment disabled, shutting down JVM")
        // wait a while for AJAX update requests to complete
        blocking(Thread.sleep(2000L))
        System.exit(EXITCODE)
      } else {
        log.info("RiffRaff not yet inactive, deferring shutdown request")
      }
    }
  }

  val sink = new DeploySink {
    def postCleanup(uuid: UUID): Unit = if (switch.isSwitchedOn) attemptShutdown()
  }

  // add hooks to listen and exit when desired
  def init() = DeployManager.subscribe(sink)
  def shutdown() = DeployManager.unsubscribe(sink)
}
