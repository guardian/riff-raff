package lifecycle

import scala.concurrent._
import ExecutionContext.Implicits.global
import controllers.{Logging, DeployController}
import magenta._
import magenta.MessageWrapper
import magenta.FinishContext
import magenta.Deploy
import com.gu.management.DefaultSwitch

object ShutdownWhenInactive extends LifecycleWithoutApp with Logging {
  val EXITCODE = 217

  // switch to enable this mode
  lazy val switch = new DefaultSwitch("shutdown-when-inactive", s"Shutdown riff-raff when there are no running deploys.  Turning this on wil cause RiffRaff to exit with exitcode $EXITCODE as soon as the last queued deploy finishes.", false) {
    override def switchOn() = {
      super.switchOn()
      // if there are currently no deploys running then shutdown immediately
      disableAndShutdown()
    }
  }

  def disableAndShutdown() {
    future {
      // wait a while for the UI requests to get up to date and then try and shutdown
      log.info("Shutdown request received, pausing for UI updates to complete")
      blocking(Thread.sleep(5000L))
      try {
        log.info("Shutdown request received, disabling deployment")
        DeployController.enableDeploysSwitch.switchOff()
        blocking(Thread.sleep(5000L))
        log.info("Enable deploys switch successfully disabled, shutting down JVM")
        System.exit(EXITCODE)
      } catch {
        case e: IllegalStateException => log.warn("Ignoring automatic shutdown request: Deploys still running")
      }
    }
  }

  def checkSwitchAndShutdown() = if (switch.isSwitchedOn) disableAndShutdown()

  val sink = new MessageSink {
    def message(wrapper: MessageWrapper) {
      wrapper.stack.messages match {
        case List(FinishContext(_),Deploy(_)) => checkSwitchAndShutdown()
        case List(FailContext(_),Deploy(_)) => checkSwitchAndShutdown()
        case _ =>
      }
    }
  }

  // add hooks to listen and exit when desired
  def init() = {
    MessageBroker.subscribe(sink)
  }
  def shutdown() = {
    MessageBroker.unsubscribe(sink)
  }
}
