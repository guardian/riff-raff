package deployment.actors

import java.util.UUID
import akka.actor.Actor
import controllers.Logging
import magenta.{DeployReporter, DeployStoppedException, DeploymentResources}
import magenta.graph.DeploymentTasks
import org.joda.time.DateTime
import utils.Agent

import scala.util.control.NonFatal

class TasksRunner(stopFlagAgent: Agent[Map[UUID, String]])
    extends Actor
    with Logging {
  import TasksRunner._

  log.debug(s"New tasks runner created with path ${self.path}")

  def receive = {
    case RunDeployment(uuid, tasks, deploymentResources, queueTime) =>
      def stopFlagAsker: Boolean = {
        stopFlagAgent().contains(uuid)
      }

      try {
        deploymentResources.reporter.infoContext(s"Deploying ${tasks.name}") {
          deployReporter =>
            try {
              tasks.tasks.zipWithIndex.foreach { case (task, index) =>
                val taskId = s"${tasks.name}/$index"
                if (stopFlagAsker) {
                  val stopMessage =
                    s"Deploy has been stopped by ${stopFlagAgent()(uuid)}"
                  deployReporter.fail(
                    stopMessage,
                    DeployStoppedException(stopMessage)
                  )
                } else {
                  log.debug(s"Running task $taskId")
                  deployReporter.taskContext(task) { taskReporter =>
                    task.execute(
                      deploymentResources.copy(reporter = taskReporter),
                      stopFlagAsker
                    )
                  }
                }
              }
              log.debug("Sending completed message")
              sender() ! DeployGroupRunner.DeploymentCompleted(tasks)
            } catch {
              case t: Throwable =>
                log.debug("Sending failed message")
                sender() ! DeployGroupRunner.DeploymentFailed(tasks, t)
                throw t
            }
        }
      } catch {
        // catch non fatal exceptions and swallow them to avoid the actor being terminated at this point
        case NonFatal(t) =>
      }

  }

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    log.debug(s"Deployment runner ${self.path} stopped")
    super.postStop()
  }
}

object TasksRunner {
  trait Message
  case class RunDeployment(
      uuid: UUID,
      deployment: DeploymentTasks,
      rootResources: DeploymentResources,
      queueTime: DateTime
  ) extends Message
}
