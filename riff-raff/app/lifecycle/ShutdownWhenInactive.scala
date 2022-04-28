package lifecycle

import controllers.Logging
import deployment.{Deployments, Record}
import magenta.DefaultSwitch

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._

trait WhenInactive extends Lifecycle with Logging {
  val name: String
  val description: String
  val deployments: Deployments

  def action(): Unit

  // switch to enable this mode
  lazy val switch: DefaultSwitch = new DefaultSwitch(name, description, false) {
    override def switchOn(): Unit = {
      super.switchOn()
      attemptAction()
    }
  }

  def attemptAction(): Unit = {
    Future {
      log.info(s"Attempting to $name: trying to atomically disable deployment")
      if (deployments.atomicDisableDeploys) {
        log.info("Deployment disabled, shutting down JVM")
        // wait a while for AJAX update requests to complete
        blocking(Thread.sleep(2000L))
        action()
      } else {
        val activeBuilds: Iterable[Record] = deployments.getControllerDeploys.filterNot(_.isDone)
        val activeBuildsLog = activeBuilds.map(record => s"${record.uuid} with status ${record.state}")
        log.info(s"RiffRaff not yet inactive (Still running: ${activeBuildsLog.mkString(", ")}), deferring $name request")
      }
    }
  }

  val sub = deployments.completed.subscribe(_ => if (switch.isSwitchedOn) attemptAction())

  // add hooks to listen and exit when desired
  def init(): Unit = { }
  def shutdown(): Unit = { sub.unsubscribe() }
}

class ShutdownWhenInactive(val deployments: Deployments) extends WhenInactive {
  val EXITCODE = 217

  override val name: String = "shutdown-when-inactive"
  override val description: String = s"Shutdown riff-raff when there are no running deploys. Turning this on will cause RiffRaff to exit with exitcode $EXITCODE as soon as the last queued deploy finishes."

  override def action(): Unit = System.exit(EXITCODE)
}
