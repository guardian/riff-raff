package deployment

import akka.actor.ActorRef
import conf.Configuration
import magenta.Output
import deployment.MessageBus.Info

class TeeLogger(left: Output, right: Output) extends Output {
  def verbose(s: => String) {
    if (Configuration.logging.verbose) { left.verbose(s); right.verbose(s) }
  }

  def info(s: => String) { left.info(s); right.info(s) }
  def warn(s: => String) { left.warn(s); right.warn(s) }
  def error(s: => String) { left.error(s); right.error(s) }

  def context[T](s: => String)(block: => T) = {
    left.context(s) {
      right.context(s) {
        block
      }
    }
  }
}

class DeployLogger(updateActor: ActorRef, taskStatus: TaskStatus) extends Output {
  def verbose(s: => String) { log(s) }
  def info(s: => String) { log(s) }
  def warn(s: => String) { log(s) }
  def error(s: => String) { log(s) }
  def log(s: => String) {
    if (!s.startsWith("tcgetattr")) {
      val currentTask = taskStatus.runningTask
      if (currentTask.isDefined) {
        taskStatus.logToTask(currentTask.get, s)
      } else {
        updateActor ! Info(LogString(s))
      }
    }
  }
  def context[T](s: => String)(block: => T) = block
}

