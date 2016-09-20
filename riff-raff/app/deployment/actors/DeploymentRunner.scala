package deployment.actors

import java.util.UUID

import akka.actor.Actor
import akka.agent.Agent
import controllers.Logging
import deployment.actors.DeployMetricsActor.{TaskComplete, TaskStart}
import magenta.DeployReporter
import magenta.graph.Deployment
import org.joda.time.DateTime

class DeploymentRunner(stopFlagAgent: Agent[Map[UUID, String]]) extends Actor with Logging {
  import DeploymentRunner._
  import DeployMetricsActor._

  log.debug(s"New deployment runner created with path ${self.path}")

  def receive = {
    case RunDeployment(uuid, deploymentNode, rootReporter, queueTime) =>

      def stopFlagAsker: Boolean = {
        stopFlagAgent().contains(uuid)
      }

      rootReporter.infoContext(s"Deployment ${deploymentNode.name}"){ deployReporter =>
        try {
          deploymentNode.tasks.zipWithIndex.foreach { case (task, index) =>
            val taskId = s"${deploymentNode.name}/$index"
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
          sender ! DeployGroupRunner.DeploymentCompleted(deploymentNode)
        } catch {
          case t:Throwable =>
            log.debug("Sending failed message")
            sender ! DeployGroupRunner.DeploymentFailed(deploymentNode, t)
        }
      }

  }

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    log.debug(s"Deployment runner ${self.path} stopped")
    super.postStop()
  }
}

object DeploymentRunner {
  trait Message
  case class RunDeployment(uuid: UUID, deployment: Deployment, rootReporter: DeployReporter, queueTime: DateTime) extends Message
}