package magenta.tasks

import magenta.{MessageBroker, Stage, KeyRing}
import com.amazonaws.services.autoscaling.model.AutoScalingGroup

case class DoubleSize(packageName: String, stage: Stage) extends ASGTask(packageName, stage) {
  def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing) {
    val doubleCapacity = asg.getDesiredCapacity * 2
    if (asg.getMaxSize < doubleCapacity)
      maxCapacity(asg.getAutoScalingGroupName, doubleCapacity)

    desiredCapacity(asg.getAutoScalingGroupName, doubleCapacity)
  }

  lazy val description = "Double the size of the auto-scaling group for package: %s, stage: %s" format (
    packageName, stage.name)
}

case class WaitTillUpAndInELB(packageName: String, stage: Stage, duration: Long) extends ASGTask(packageName, stage)
    with RepeatedPollingCheck {

  def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing) {
    check {
      val updatedAsg = refresh(asg)
      atDesiredCapacity(updatedAsg) && allInELB(updatedAsg)
    }
  }

  def description: String = "Check all hosts in an ASG are up and in ELB"
}

abstract class ASGTask(packageName: String, stage: Stage) extends Task with ASG {
  def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing)

  def execute(keyRing: KeyRing) {
    implicit val key = keyRing

    withPackageAndStage(packageName, stage) match {
      case Some(asg) => execute(asg)
      case None => MessageBroker.fail(
        "No autoscaling group found with tags: App -> %s, Stage -> %s" format (packageName, stage.name))
    }
  }

  def verbose = description

  val taskHosts = Nil
}