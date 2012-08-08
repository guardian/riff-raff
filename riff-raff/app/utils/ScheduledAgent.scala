package utils

import akka.actor.ActorSystem
import akka.agent.Agent
import controllers.Logging
import akka.util.{Timeout, Duration}

object ScheduledAgent {
  val scheduleSystem = ActorSystem("scheduled-agent")

  def apply[T](initialDelay: Duration, frequency: Duration)(block: => T): ScheduledAgent[T] = {
    new ScheduledAgent(initialDelay, frequency, block, scheduleSystem)
  }

  def shutdown() {
    scheduleSystem.shutdown()
  }
}

class ScheduledAgent[T](initialDelay: Duration, frequency: Duration, block: => T, system: ActorSystem) extends Logging {

  var updateCalled = false
  val agent = Agent[Option[T]](None)(system)

  val agentSchedule = system.scheduler.schedule(initialDelay, frequency) {
    update()
  }

  def update() {
    agent update(Some(block))
    updateCalled = true
  }

  def get(): T =
    if (agent().isDefined) agent().get
    else {
      if (!updateCalled) {
        update()
      }
      val timeout = Timeout(frequency)
      agent.await(timeout).get
    }

  def apply(): T = get

  def shutdown() {
    agentSchedule.cancel()
  }

}
