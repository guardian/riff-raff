package deployment.actors

import java.util.UUID

import akka.actor.Actor
import akka.agent.Agent
import controllers.Logging
import magenta.DeployReporter
import magenta.graph.DeploymentTasks
import org.joda.time.DateTime

class TasksRunner(stopFlagAgent: Agent[Map[UUID, String]]) extends Actor with Logging {
  import DeployMetricsActor._
  import TasksRunner._

  log.debug(s"New tasks runner created with path ${self.path}")

  def receive = {
    case RunDeployment(uuid, tasks, rootReporter, queueTime) =>

      def stopFlagAsker: Boolean = {
        stopFlagAgent().contains(uuid)
      }

      rootReporter.infoContext(s"Deploying ${tasks.name}"){ deployReporter =>
        try {
          tasks.tasks.zipWithIndex.foreach { case (task, index) =>
            val taskId = s"${tasks.name}/$index"
            try {
              log.debug(s"Running task $taskId")
              deployMetricsProcessor ! TaskStart(uuid, taskId, queueTime, new DateTime())
              deployReporter.taskContext(task) { taskReporter =>
                task.execute(taskReporter, stopFlagAsker)
              }
            } finally {
              deployMetricsProcessor ! TaskComplete(uuid, taskId, new DateTime())
            }
          }
          log.debug("Sending completed message")
          sender ! DeployGroupRunner.DeploymentCompleted(tasks)
        } catch {
          case t:Throwable =>
            log.debug("Sending failed message")
            sender ! DeployGroupRunner.DeploymentFailed(tasks, t)
        }
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
  case class RunDeployment(uuid: UUID, deployment: DeploymentTasks, rootReporter: DeployReporter, queueTime: DateTime) extends Message
}