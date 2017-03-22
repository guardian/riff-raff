package lifecycle

import com.gu.management.DefaultSwitch
import controllers.Logging
import deployment.{Deployments, Record}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._

object ShutdownWhenInactive extends Lifecycle with Logging {
  val EXITCODE = 217

  // switch to enable this mode
  lazy val switch = new DefaultSwitch("shutdown-when-inactive", s"Shutdown riff-raff when there are no running deploys. Turning this on will cause RiffRaff to exit with exitcode $EXITCODE as soon as the last queued deploy finishes.", false) {
    override def switchOn() = {
      super.switchOn()
      // try and shutdown immediately
      attemptShutdown()
    }
  }

  def attemptShutdown() {
    Future {
      log.info("Attempting to shutdown: trying to atomically disable deployment")
      if (Deployments.atomicDisableDeploys) {
        log.info("Deployment disabled, shutting down JVM")
        // wait a while for AJAX update requests to complete
        blocking(Thread.sleep(2000L))
        System.exit(EXITCODE)
      } else {
        val activeBuilds: Iterable[Record] = Deployments.getControllerDeploys.filterNot(_.isDone)
        val activeBuildsString = activeBuilds.map(b => s"'${b.buildName} ${b.buildId}'").mkString(", ")
        log.info(s"RiffRaff not yet inactive ($activeBuildsString ${if(activeBuilds.size > 1) "are" else "is"} still running), deferring shutdown request")
      }
    }
  }

  val sub = Deployments.completed.subscribe(_ => if (switch.isSwitchedOn) attemptShutdown())

  // add hooks to listen and exit when desired
  def init() { }
  def shutdown() { sub.unsubscribe() }
}
