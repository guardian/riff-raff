package utils

import akka.actor.ActorSystem
import akka.agent.Agent
import controllers.Logging
import akka.util.{Timeout, Duration}
import lifecycle.LifecycleWithoutApp

object ScheduledAgent extends LifecycleWithoutApp {
  val scheduleSystem = ActorSystem("scheduled-agent")

  def apply[T](initialDelay: Duration, frequency: Duration)(block: => T): ScheduledAgent[T] = {
    ScheduledAgent(initialDelay, frequency, block)(_ => block)
  }

  def apply[T](initialDelay: Duration, frequency: Duration, initialValue: T)(block: T => T): ScheduledAgent[T] = {
    ScheduledAgent(initialValue, ScheduledAgentUpdate(block, initialDelay, frequency))
  }

  def apply[T](initialValue: T, updates: ScheduledAgentUpdate[T]*): ScheduledAgent[T] = {
    new ScheduledAgent(scheduleSystem, initialValue, updates:_*)
  }

  def init() {}

  def shutdown() {
    scheduleSystem.shutdown()
  }
}

case class ScheduledAgentUpdate[T](block: T => T, initialDelay: Duration, frequency: Duration)

object ScheduledAgentUpdate {
  def apply[T](initialDelay: Duration, frequency: Duration)(block: T => T): ScheduledAgentUpdate[T] = {
    ScheduledAgentUpdate(block, initialDelay, frequency)
  }
}

class ScheduledAgent[T](system: ActorSystem, initialValue: T, updates: ScheduledAgentUpdate[T]*) extends Logging {

  val agent = Agent[T](initialValue)(system)

  val updateCancellables = updates.map { update =>
    system.scheduler.schedule(update.initialDelay, update.frequency) {
      queueUpdate(update)
    }
  }

  def queueUpdate(update: ScheduledAgentUpdate[T]) {
    agent sendOff{ lastValue =>
      try {
        update.block(lastValue)
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
    updateCancellables.foreach(_.cancel())
  }

}
