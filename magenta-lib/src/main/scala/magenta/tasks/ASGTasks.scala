package magenta.tasks

import magenta._
import com.amazonaws.AmazonServiceException
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import collection.JavaConversions._
import magenta.KeyRing
import magenta.Stage

case class CheckGroupSize(pkg: DeploymentPackage, stage: Stage, stack: Stack)(implicit val keyRing: KeyRing) extends ASGTask {
  override def execute(asg: AutoScalingGroup, logger: DeployLogger, stopFlag: => Boolean) {
    val doubleCapacity = asg.getDesiredCapacity * 2
    if (asg.getMaxSize < doubleCapacity || doubleCapacity == 0) {
      logger.fail(
        s"Autoscaling group does not have the capacity to deploy current max = ${asg.getMaxSize} - desired max = $doubleCapacity"
      )
    }
  }

  lazy val description = "Checking there is enough capacity to deploy"
}

case class TagCurrentInstancesWithTerminationTag(pkg: DeploymentPackage, stage: Stage, stack: Stack)(implicit val keyRing: KeyRing) extends ASGTask {
  override def execute(asg: AutoScalingGroup, logger: DeployLogger, stopFlag: => Boolean) {
    EC2.setTag(asg.getInstances.toList, "Magenta", "Terminate")(keyRing)
  }

  lazy val description = "Tag existing instances of the auto-scaling group for termination"
}

case class DoubleSize(pkg: DeploymentPackage, stage: Stage, stack: Stack)(implicit val keyRing: KeyRing) extends ASGTask {

  override def execute(asg: AutoScalingGroup, logger: DeployLogger, stopFlag: => Boolean) {
    desiredCapacity(asg.getAutoScalingGroupName, asg.getDesiredCapacity * 2)(keyRing)
  }

  lazy val description = s"Double the size of the auto-scaling group in $stage, $stack for apps ${pkg.apps.mkString(", ")}"
}

sealed abstract class Pause(durationMillis: Long)(implicit val keyRing: KeyRing) extends Task {

  override def execute(logger: DeployLogger, stopFlag: => Boolean) {
    Thread.sleep(durationMillis)
  }

  def description = verbose
}

case class HealthcheckGrace(durationMillis: Long)(implicit keyRing: KeyRing) extends Pause(durationMillis) {
  def verbose: String = s"Wait extra ${durationMillis}ms to let Load Balancer report correctly"
}

case class WarmupGrace(durationMillis: Long)(implicit keyRing: KeyRing) extends Pause(durationMillis) {
  def verbose: String = s"Wait extra ${durationMillis}ms to let instances in Load Balancer warm up"
}

case class CheckForStabilization(pkg: DeploymentPackage, stage: Stage, stack: Stack)(implicit val keyRing: KeyRing) extends ASGTask {
  override def execute(asg: AutoScalingGroup, logger: DeployLogger, stopFlag: => Boolean) {
    isStabilized(asg)
  }
  lazy val description: String = "Check the desired number of hosts in both the ASG and ELB are up and that the number of hosts match"
}

case class WaitForStabilization(pkg: DeploymentPackage, stage: Stage, stack: Stack, duration: Long)(implicit val keyRing: KeyRing) extends ASGTask
    with SlowRepeatedPollingCheck {

  override def execute(asg: AutoScalingGroup, logger: DeployLogger, stopFlag: => Boolean) {
    check(logger, stopFlag) {
      try {
        isStabilized(refresh(asg))
      } catch {
        case e: AmazonServiceException if isRateExceeded(e) => {
          logger.info(e.getMessage)
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
  override def execute(asg: AutoScalingGroup, logger: DeployLogger, stopFlag: => Boolean) {
    val instancesToKill = asg.getInstances.filter(instance => EC2.hasTag(instance, "Magenta", "Terminate")(keyRing))
    val orderedInstancesToKill = instancesToKill.transposeBy(_.getAvailabilityZone)
    orderedInstancesToKill.foreach(instance => cull(asg, instance)(keyRing))
  }

  lazy val description = "Terminate instances with the termination tag for this deploy"
}

case class SuspendAlarmNotifications(pkg: DeploymentPackage, stage: Stage, stack: Stack)(implicit val keyRing: KeyRing) extends ASGTask {

  override def execute(asg: AutoScalingGroup, logger: DeployLogger, stopFlag: => Boolean) {
    suspendAlarmNotifications(asg.getAutoScalingGroupName)(keyRing)
  }

  lazy val description = "Suspending Alarm Notifications - group will no longer scale on any configured alarms"
}

case class ResumeAlarmNotifications(pkg: DeploymentPackage, stage: Stage, stack: Stack)(implicit val keyRing: KeyRing) extends ASGTask {

  override def execute(asg: AutoScalingGroup, logger: DeployLogger, stopFlag: => Boolean) {
    resumeAlarmNotifications(asg.getAutoScalingGroupName)(keyRing)
  }

  lazy val description = "Resuming Alarm Notifications - group will scale on any configured alarms"
}

trait ASGTask extends Task with ASG {
  def pkg: DeploymentPackage
  def stage: Stage
  def stack: Stack

  def execute(asg: AutoScalingGroup, logger: DeployLogger, stopFlag: => Boolean)

  override def execute(logger: DeployLogger, stopFlag: => Boolean) {
    implicit val key = keyRing
    implicit val dl = logger

    val group = groupForAppAndStage(pkg, stage, stack)
    execute(group, logger, stopFlag)
  }

  def verbose = description
}
