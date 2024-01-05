package deployment.actors

import java.util.UUID

import org.apache.pekko.actor.SupervisorStrategy.Stop
import org.apache.pekko.actor.{
  Actor,
  ActorRef,
  ActorRefFactory,
  OneForOneStrategy,
  Terminated
}
import org.apache.pekko.agent.Agent
import controllers.Logging
import deployment.Record

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

class DeployCoordinator(
    val deployGroupRunnerFactory: (
        ActorRefFactory,
        Record,
        ActorRef
    ) => ActorRef,
    maxDeploys: Int,
    stopFlagAgent: Agent[Map[UUID, String]]
) extends Actor
    with Logging {

  override def supervisorStrategy() = OneForOneStrategy() { case throwable =>
    log.warn("DeployGroupRunner died with exception", throwable)
    Stop
  }

  var deployRunners = Map.empty[UUID, (Record, ActorRef)]
  var deferredDeployQueue = ListBuffer[DeployCoordinator.Message]()

  private def schedulable(recordToSchedule: Record): Boolean = {
    deployRunners.size < maxDeploys &&
    !deployRunners.values.exists { case (record, actor) =>
      record.parameters.build.projectName == recordToSchedule.parameters.build.projectName &&
      record.parameters.stage == recordToSchedule.parameters.stage
    }
  }

  private def cleanup(uuid: UUID): Unit = {
    log.debug("Cleaning up")

    deferredDeployQueue.foreach(self ! _)
    deferredDeployQueue.clear()

    deployRunners -= uuid
  }

  import DeployCoordinator._

  def receive = {
    case StartDeploy(record) if !schedulable(record) =>
      log.debug("Not schedulable, queuing")
      deferredDeployQueue += StartDeploy(record)

    case StartDeploy(record) if schedulable(record) =>
      log.debug("Scheduling deploy")
      try {
        val deployGroupRunner =
          context.watch(deployGroupRunnerFactory(context, record, context.self))
        deployRunners += (record.uuid -> (record, deployGroupRunner))
        deployGroupRunner ! DeployGroupRunner.Start
      } catch {
        case NonFatal(t) => log.error("Couldn't schedule deploy", t)
      }

    case CleanupDeploy(uuid) =>
      cleanup(uuid)

    case Terminated(actor) =>
      val maybeUUID =
        deployRunners.find { case (_, (_, ref)) => ref == actor }.map(_._1)
      maybeUUID.foreach { uuid =>
        log.warn(
          s"Received premature terminate from ${actor.path} (had not been cleaned up)"
        )
        cleanup(uuid)
      }

  }
}

object DeployCoordinator {
  trait Message
  case class StartDeploy(record: Record) extends Message
  case class CleanupDeploy(uuid: UUID) extends Message
}
