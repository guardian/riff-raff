package magenta.tasks

import magenta._
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import collection.JavaConversions._
import magenta.KeyRing
import magenta.Stage
import magenta.DeploymentPackage
import magenta.deployment_type.Param

object CheckGroupSize extends ApplicationTaskType {
  def apply(pkg: DeploymentPackage, parameters: DeployParameters) = CheckGroupSize(pkg.name, parameters.stage)
  lazy val description = "Check there is enough capacity to deploy"
}

case class CheckGroupSize(packageName: String, stage: Stage) extends ASGTask {
  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    val doubleCapacity = asg.getDesiredCapacity * 2
    if (asg.getMaxSize < doubleCapacity) {
      MessageBroker.fail(
        "Autoscaling group does not have the capacity to deploy current max = %d - desired max = %d" format (asg.getMaxSize, doubleCapacity))
    }
  }

  lazy val description = CheckGroupSize.description
}

object TagCurrentInstancesWithTerminationTag extends ApplicationTaskType {
  def description = "Tag existing instances of the auto-scaling group for termination"
  def apply(pkg: DeploymentPackage, parameters: DeployParameters) = TagCurrentInstancesWithTerminationTag(pkg.name, parameters.stage)
}

case class TagCurrentInstancesWithTerminationTag(packageName: String, stage: Stage) extends ASGTask {
  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    EC2.setTag(asg.getInstances.toList, "Magenta", "Terminate")
  }

  lazy val description = TagCurrentInstancesWithTerminationTag.description
}

object DoubleSize extends ApplicationTaskType {
  def description = "Double the size of the auto-scaling group"
  def apply(pkg: DeploymentPackage, parameters: DeployParameters) = DoubleSize(pkg.name, parameters.stage)
}

case class DoubleSize(packageName: String, stage: Stage) extends ASGTask {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    desiredCapacity(asg.getAutoScalingGroupName, asg.getDesiredCapacity * 2)
  }

  lazy val description = s"${DoubleSize.description} for package: $packageName, stage: ${stage.name}"
}

case class HealthcheckGrace(healthcheckGrace: Param[Int]) extends ApplicationTaskType {
  def description = s"Wait for a grace period before checking status"
  def apply(pkg: DeploymentPackage, parameters: DeployParameters) = HealthcheckGraceTask(healthcheckGrace(pkg) * 1000)
}

case class HealthcheckGraceTask(duration: Long) extends Task {

  def execute(sshCredentials: KeyRing, stopFlag: => Boolean) {
    Thread.sleep(duration)
  }

  def verbose: String = s"Wait extra ${duration}ms to let Load Balancer report correctly"

  def description = verbose
}

case class WaitForStabilization(secondsToWait: Param[Int]) extends ApplicationTaskType {
  def description = "Check the desired number of hosts in ASG are up and in ELB"
  def apply(pkg: DeploymentPackage, parameters: DeployParameters) =
    WaitForStabilizationTask(pkg.name, parameters.stage, secondsToWait(pkg) * 1000)
}

case class WaitForStabilizationTask(packageName: String, stage: Stage, duration: Long) extends ASGTask
    with SlowRepeatedPollingCheck {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    check(stopFlag) {
      isStabilized(refresh(asg))
    }
  }

  lazy val description: String = "Check the desired number of hosts in ASG are up and in ELB"
//  WaitForStabilization.description
}

object CullInstancesWithTerminationTag extends ApplicationTaskType {
  def description = "Terminate instances with the termination tag for this deploy"
  def apply(pkg: DeploymentPackage, parameters: DeployParameters) = CullInstancesWithTerminationTag(pkg.name, parameters.stage)
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

object SuspendAlarmNotifications extends ApplicationTaskType {
  def description = "Suspend alarm notifications - group will no longer scale on any configured alarms"
  def apply(pkg: DeploymentPackage, parameters: DeployParameters) = SuspendAlarmNotifications(pkg.name, parameters.stage)
}

case class SuspendAlarmNotifications(packageName: String, stage: Stage) extends ASGTask {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    suspendAlarmNotifications(asg.getAutoScalingGroupName)
  }

  lazy val description = SuspendAlarmNotifications.description
}

object ResumeAlarmNotifications extends ApplicationTaskType {
  def description = "Resuming Alarm Notifications - group will scale on any configured alarms"
  def apply(pkg: DeploymentPackage, parameters: DeployParameters) = ResumeAlarmNotifications(pkg.name, parameters.stage)
}

case class ResumeAlarmNotifications(packageName: String, stage: Stage) extends ASGTask {

  def execute(asg: AutoScalingGroup, stopFlag: => Boolean)(implicit keyRing: KeyRing) {
    resumeAlarmNotifications(asg.getAutoScalingGroupName)
  }

  lazy val description = ResumeAlarmNotifications.description
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