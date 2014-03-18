package magenta.tasks

import magenta._
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import collection.JavaConversions._
import magenta.KeyRing
import magenta.Stage

case class CheckGroupSize(pkg: DeploymentPackage, stage: Stage, stack: Stack) extends ASGTask {
  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    val doubleCapacity = asg.getDesiredCapacity * 2
    if (asg.getMaxSize < doubleCapacity) {
      MessageBroker.fail(
        s"Autoscaling group does not have the capacity to deploy current max = ${asg.getMaxSize} - desired max = $doubleCapacity"
      )
    }
  }

  lazy val description = "Checking there is enough capacity to deploy"
}

case class TagCurrentInstancesWithTerminationTag(pkg: DeploymentPackage, stage: Stage, stack: Stack) extends ASGTask {
  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    EC2.setTag(asg.getInstances.toList, "Magenta", "Terminate")
  }

  lazy val description = "Tag existing instances of the auto-scaling group for termination"
}

case class DoubleSize(pkg: DeploymentPackage, stage: Stage, stack: Stack) extends ASGTask {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    desiredCapacity(asg.getAutoScalingGroupName, asg.getDesiredCapacity * 2)
  }

  lazy val description = s"Double the size of the auto-scaling group in $stage for apps ${pkg.apps.mkString(", ")}"
}

case class HealthcheckGrace(duration: Long) extends Task {

  def execute(sshCredentials: KeyRing, stopFlag: => Boolean) {
    Thread.sleep(duration)
  }

  def verbose: String = s"Wait extra ${duration}ms to let Load Balancer report correctly"

  def description = verbose
}

case class WaitForStabilization(pkg: DeploymentPackage, stage: Stage, stack: Stack, duration: Long) extends ASGTask
    with SlowRepeatedPollingCheck {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    check(stopFlag) {
      try {
        isStabilized(refresh(asg))
      } catch {
        case e: AmazonServiceException if isRateExceeded(e) => {
          MessageBroker.info(e.getMessage)
          false
        }
      }
    }

    //found this out by good old trial and error
    def isRateExceeded(e: AmazonServiceException) = e.getStatusCode == 400 && e.getErrorCode == "Throttling"
  }

  lazy val description: String = "Check the desired number of hosts in ASG are up and in ELB"
}

case class CullInstancesWithTerminationTag(pkg: DeploymentPackage, stage: Stage, stack: Stack) extends ASGTask {
  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    for (instance <- asg.getInstances) {
      if (EC2.hasTag(instance, "Magenta", "Terminate")) {
        cull(asg, instance)
      }
    }
  }

  lazy val description = "Terminate instances with the termination tag for this deploy"
}

case class SuspendAlarmNotifications(pkg: DeploymentPackage, stage: Stage, stack: Stack) extends ASGTask {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    suspendAlarmNotifications(asg.getAutoScalingGroupName)
  }

  lazy val description = "Suspending Alarm Notifications - group will no longer scale on any configured alarms"
}

case class ResumeAlarmNotifications(pkg: DeploymentPackage, stage: Stage, stack: Stack) extends ASGTask {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    resumeAlarmNotifications(asg.getAutoScalingGroupName)
  }

  lazy val description = "Resuming Alarm Notifications - group will scale on any configured alarms"
}

trait ASGTask extends Task with ASG {
  def pkg: DeploymentPackage
  def stage: Stage
  def stack: Stack

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing)

  override def execute(keyRing: KeyRing, stopFlag: => Boolean) {
    implicit val key = keyRing

    val group = groupForAppAndStage(pkg, stage, stack)
    execute(group, stopFlag)
  }

  def verbose = description
}