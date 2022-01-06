package lifecycle

import controllers.Logging
import deployment.{Deployments, Record}
import magenta.DefaultSwitch

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._

class ShutdownWhenInactive(deployments: Deployments) extends Lifecycle with Logging {
  val EXITCODE = 217

  // switch to enable this mode
  lazy val switch = new DefaultSwitch("shutdown-when-inactive", s"Shutdown riff-raff when there are no running deploys. Turning this on will cause RiffRaff to exit with exitcode $EXITCODE as soon as the last queued deploy finishes.", false) {
    override def switchOn() = {
      super.switchOn()
      // try and shutdown immediately
      attemptShutdown()
    }
  }

  def attemptShutdown(): Unit = {
    Future {
      log.info("Attempting to shutdown: trying to atomically disable deployment")
      if (deployments.atomicDisableDeploys) {
        log.info("Deployment disabled, shutting down JVM")
        // wait a while for AJAX update requests to complete
        blocking(Thread.sleep(2000L))
        System.exit(EXITCODE)
      } else {
        val activeBuilds: Iterable[Record] = deployments.getControllerDeploys.filterNot(_.isDone)
        val activeBuildsLog = activeBuilds.map(record => s"${record.uuid} with status ${record.state}")
        log.info(s"RiffRaff not yet inactive (Still running: ${activeBuildsLog.mkString(", ")}), deferring shutdown request")
      }
    }
  }

  val sub = deployments.completed.subscribe(_ => if (switch.isSwitchedOn) attemptShutdown())

  // add hooks to listen and exit when desired
  def init(): Unit = { }
  def shutdown(): Unit = { sub.unsubscribe() }
}
