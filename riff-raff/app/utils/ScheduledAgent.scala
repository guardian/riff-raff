package utils

import akka.actor.ActorSystem
import akka.agent.Agent
import controllers.Logging
import akka.util.{Timeout, Duration}
import lifecycle.LifecycleWithoutApp

object ScheduledAgent extends LifecycleWithoutApp {
  val scheduleSystem = ActorSystem("scheduled-agent")

  def apply[T](initialDelay: Duration, frequency: Duration)(block: => T): ScheduledAgent[T] = {
    new ScheduledAgent(initialDelay, frequency, block, _ => block, scheduleSystem)
  }

  def apply[T](initialDelay: Duration, frequency: Duration, initialValue: T)(block: T => T): ScheduledAgent[T] = {
    new ScheduledAgent(initialDelay, frequency, initialValue, block, scheduleSystem)
  }

  def init() {}

  def shutdown() {
    scheduleSystem.shutdown()
  }
}

class ScheduledAgent[T](initialDelay: Duration, frequency: Duration, initialValue: T, block: T => T, system: ActorSystem) extends Logging {

  val agent = Agent[T](initialValue)(system)

  val agentSchedule = system.scheduler.schedule(initialDelay, frequency) {
    agent sendOff{ lastValue =>
      try {
        block(lastValue)
      } catch {
        case t:Throwable =>
          log.warn("Failed to update on schedule", t)
          lastValue
      }
    }
  }

  def get(): T = agent()
  def apply(): T = get()

  def shutdown() {
    agentSchedule.cancel()
  }

}
