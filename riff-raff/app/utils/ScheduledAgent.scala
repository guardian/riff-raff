package utils

import akka.actor.{Cancellable, ActorSystem}
import akka.agent.Agent
import controllers.Logging
import akka.util.{Timeout, Duration}
import lifecycle.LifecycleWithoutApp
import org.joda.time._
import akka.util.duration._

object ScheduledAgent extends LifecycleWithoutApp {
  val scheduleSystem = ActorSystem("scheduled-agent")

  def apply[T](initialDelay: Duration, frequency: Duration)(block: => T): ScheduledAgent[T] = {
    ScheduledAgent(initialDelay, frequency, block)(_ => block)
  }

  def apply[T](initialDelay: Duration, frequency: Duration, initialValue: T)(block: T => T): ScheduledAgent[T] = {
    ScheduledAgent(initialValue, PeriodicScheduledAgentUpdate(block, initialDelay, frequency))
  }

  def apply[T](initialValue: T, updates: ScheduledAgentUpdate[T]*): ScheduledAgent[T] = {
    new ScheduledAgent(scheduleSystem, initialValue, updates:_*)
  }

  def init() {}

  def shutdown() {
    scheduleSystem.shutdown()
  }
}

trait ScheduledAgentUpdate[T] {
  def block: T => T
}

case class PeriodicScheduledAgentUpdate[T](block: T => T, initialDelay: Duration, frequency: Duration) extends ScheduledAgentUpdate[T]

object PeriodicScheduledAgentUpdate {
  def apply[T](initialDelay: Duration, frequency: Duration)(block: T => T): PeriodicScheduledAgentUpdate[T] =
    PeriodicScheduledAgentUpdate(block, initialDelay, frequency)
}

case class DailyScheduledAgentUpdate[T](block: T => T, timeOfDay: LocalTime) extends ScheduledAgentUpdate[T] {
  def timeToNextExecution: Duration = {
    val executionToday = (new LocalDate()).toDateTime(timeOfDay)

    val interval = if (executionToday.isAfterNow)
      // today if before the time of day
      new Interval(new DateTime(), executionToday)
    else {
      // tomorrow if after the time of day
      val executionTomorrow = (new LocalDate()).plusDays(1).toDateTime(timeOfDay)
      new Interval(new DateTime(), executionTomorrow)
    }
    interval.toDurationMillis milliseconds
  }
}
object DailyScheduledAgentUpdate {
  def apply[T](timeOfDay: LocalTime)(block: T => T): DailyScheduledAgentUpdate[T] = DailyScheduledAgentUpdate(block, timeOfDay)
}

class ScheduledAgent[T](system: ActorSystem, initialValue: T, updates: ScheduledAgentUpdate[T]*) extends Logging {

  val agent = Agent[T](initialValue)(system)

  val cancellablesAgent = Agent[Map[ScheduledAgentUpdate[T],Cancellable]]{
    updates.map { update =>
      val cancellable = update match {
        case periodic:PeriodicScheduledAgentUpdate[_] =>
          system.scheduler.schedule(periodic.initialDelay, periodic.frequency) {
            queueUpdate(periodic)
          }
        case daily:DailyScheduledAgentUpdate[_] =>
          scheduleNext(daily.asInstanceOf[DailyScheduledAgentUpdate[T]])
      }
      update -> cancellable
    }.toMap
  }(system)

  def scheduleNext(update: DailyScheduledAgentUpdate[T]): Cancellable = {
    val delay = update.timeToNextExecution
    log.debug("Scheduling %s to run next in %s" format (update, delay))
    system.scheduler.scheduleOnce(delay) {
      queueUpdate(update)
      val newCancellable = scheduleNext(update)
      cancellablesAgent.send(_ + (update -> newCancellable))
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
    cancellablesAgent.send { cancellables =>
      cancellables.values.foreach(_.cancel())
      Map.empty
    }
  }

}
