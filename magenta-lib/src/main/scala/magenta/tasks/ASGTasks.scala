package magenta.tasks

import magenta._
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import collection.JavaConversions._
import magenta.KeyRing
import magenta.Stage

case class CheckGroupSize(pkg: DeploymentPackage, stage: Stage, stack: Stack)(implicit val keyRing: KeyRing) extends ASGTask {
  def execute(asg: AutoScalingGroup, stopFlag: => Boolean) {
    val doubleCapacity = asg.getDesiredCapacity * 2
    if (asg.getMaxSize < doubleCapacity) {
      MessageBroker.fail(
        s"Autoscaling group does not have the capacity to deploy current max = ${asg.getMaxSize} - desired max = $doubleCapacity"
      )
    }
  }

  lazy val description = "Checking there is enough capacity to deploy"
}

case class TagCurrentInstancesWithTerminationTag(pkg: DeploymentPackage, stage: Stage, stack: Stack)(implicit val keyRing: KeyRing) extends ASGTask {
  def execute(asg: AutoScalingGroup, stopFlag: => Boolean) {
    EC2.setTag(asg.getInstances.toList, "Magenta", "Terminate")(keyRing)
  }

  lazy val description = "Tag existing instances of the auto-scaling group for termination"
}

case class DoubleSize(pkg: DeploymentPackage, stage: Stage, stack: Stack)(implicit val keyRing: KeyRing) extends ASGTask {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean) {
    desiredCapacity(asg.getAutoScalingGroupName, asg.getDesiredCapacity * 2)(keyRing)
  }

  lazy val description = s"Double the size of the auto-scaling group in $stage for apps ${pkg.apps.mkString(", ")}"
}

case class HealthcheckGrace(duration: Long)(implicit val keyRing: KeyRing) extends Task {

  def execute(stopFlag: => Boolean) {
    Thread.sleep(duration)
  }

  def verbose: String = s"Wait extra ${duration}ms to let Load Balancer report correctly"

  def description = verbose
}

case class CheckForStabilization(pkg: DeploymentPackage, stage: Stage, stack: Stack)(implicit val keyRing: KeyRing) extends ASGTask {
  def execute(asg: AutoScalingGroup, stopFlag: => Boolean) {
    isStabilized(asg)
  }
  lazy val description: String = "Check the desired number of hosts in both the ASG and ELB are up and that the number of hosts match"
}

case class WaitForStabilization(pkg: DeploymentPackage, stage: Stage, stack: Stack, duration: Long)(implicit val keyRing: KeyRing) extends ASGTask
    with SlowRepeatedPollingCheck {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean) {
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

  lazy val description: String = "Check the desired number of hosts in both the ASG and ELB are up and that the number of hosts match"
}

case class CullInstancesWithTerminationTag(pkg: DeploymentPackage, stage: Stage, stack: Stack)(implicit val keyRing: KeyRing) extends ASGTask {
  def execute(asg: AutoScalingGroup, stopFlag: => Boolean) {
    val instancesToKill = asg.getInstances.filter(instance => EC2.hasTag(instance, "Magenta", "Terminate")(keyRing))
    val orderedInstancesToKill = instancesToKill.transposeBy(_.getAvailabilityZone)
    orderedInstancesToKill.foreach(instance => cull(asg, instance)(keyRing))
  }

  lazy val description = "Terminate instances with the termination tag for this deploy"
}

case class SuspendAlarmNotifications(pkg: DeploymentPackage, stage: Stage, stack: Stack)(implicit val keyRing: KeyRing) extends ASGTask {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean) {
    suspendAlarmNotifications(asg.getAutoScalingGroupName)(keyRing)
  }

  lazy val description = "Suspending Alarm Notifications - group will no longer scale on any configured alarms"
}

case class ResumeAlarmNotifications(pkg: DeploymentPackage, stage: Stage, stack: Stack)(implicit val keyRing: KeyRing) extends ASGTask {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean) {
    resumeAlarmNotifications(asg.getAutoScalingGroupName)(keyRing)
  }

  lazy val description = "Resuming Alarm Notifications - group will scale on any configured alarms"
}

trait ASGTask extends Task with ASG {
  def pkg: DeploymentPackage
  def stage: Stage
  def stack: Stack

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)

  override def execute(stopFlag: => Boolean) {
    implicit val key = keyRing

    val group = groupForAppAndStage(pkg, stage, stack)
    execute(group, stopFlag)
  }

  def verbose = description
}