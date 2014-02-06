package magenta.tasks

import magenta.{MessageBroker, Stage, KeyRing}
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import collection.JavaConversions._

case class CheckGroupSize(packageName: String, stage: Stage) extends ASGTask {
  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    val doubleCapacity = asg.getDesiredCapacity * 2
    if (asg.getMaxSize < doubleCapacity) {
      MessageBroker.fail(
        "Autoscaling group does not have the capacity to deploy current max = %d - desired max = %d" format (asg.getMaxSize, doubleCapacity))
    }
  }

  lazy val description = "Checking there is enough capacity to deploy" format (
    packageName, stage.name)
}

case class TagCurrentInstancesWithTerminationTag(packageName: String, stage: Stage) extends ASGTask {
  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    EC2.setTag(asg.getInstances.toList, "Magenta", "Terminate")
  }

  lazy val description = "Tag existing instances of the auto-scaling group for termination"
}

case class DoubleSize(packageName: String, stage: Stage) extends ASGTask {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    desiredCapacity(asg.getAutoScalingGroupName, asg.getDesiredCapacity * 2)
  }

  lazy val description = "Double the size of the auto-scaling group for package: %s, stage: %s" format (
    packageName, stage.name)
}

case class HealthcheckGrace(duration: Long) extends Task {

  def execute(sshCredentials: KeyRing, stopFlag: => Boolean) {
    Thread.sleep(duration)
  }

  def verbose: String = s"Wait extra ${duration}ms to let Load Balancer report correctly"

  def description = verbose
}

case class WaitForStabilization(packageName: String, stage: Stage, duration: Long) extends ASGTask
    with SlowRepeatedPollingCheck {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    check(stopFlag) {
      try {
        isStabilized(refresh(asg))
      } catch {
        case e: AmazonServiceException if isRateExceeded(e) => false
      }
    }

    //found this out by good old trial and error
    def isRateExceeded(e: AmazonServiceException) = e.getStatusCode == 400 && e.getErrorCode == "Throttling"
  }

  lazy val description: String = "Check the desired number of hosts in ASG are up and in ELB"
}

case class CullInstancesWithTerminationTag(packageName: String, stage: Stage) extends ASGTask {
  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    for (instance <- asg.getInstances) {
      if (EC2.hasTag(instance, "Magenta", "Terminate")) {
        cull(asg, instance)
      }
    }
  }

  lazy val description = "Terminate instances with the termination tag for this deploy"
}

case class SuspendAlarmNotifications(packageName: String, stage: Stage) extends ASGTask {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    suspendAlarmNotifications(asg.getAutoScalingGroupName)
  }

  lazy val description = "Suspending Alarm Notifications - group will no longer scale on any configured alarms"
}

case class ResumeAlarmNotifications(packageName: String, stage: Stage) extends ASGTask {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    resumeAlarmNotifications(asg.getAutoScalingGroupName)
  }

  lazy val description = "Resuming Alarm Notifications - group will scale on any configured alarms"
}

trait ASGTask extends Task with ASG {
  def packageName: String
  def stage: Stage

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing)

  override def execute(keyRing: KeyRing, stopFlag: => Boolean) {
    implicit val key = keyRing

    withPackageAndStage(packageName, stage) match {
      case Some(asg) => execute(asg, stopFlag)
      case None => MessageBroker.fail(
        "No autoscaling group found with tags: App -> %s, Stage -> %s" format (packageName, stage.name))
    }
  }

  def verbose = description
}