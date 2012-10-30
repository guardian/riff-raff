package magenta.tasks

import magenta.{Build, MessageBroker, Stage, KeyRing}
import com.amazonaws.services.autoscaling.model.{Instance, AutoScalingGroup}
import collection.JavaConversions._

case class TagCurrentInstancesWithTerminationTag(packageName: String, stage: Stage) extends ASGTask {
  def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing) {
    EC2.setTag(asg.getInstances.toList, "Magenta", "Terminate")
  }

  lazy val description = "Tag existing instances of the auto-scaling group for termination"
}

case class DoubleSize(packageName: String, stage: Stage) extends ASGTask {
  def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing) {
    val doubleCapacity = asg.getDesiredCapacity * 2
    if (asg.getMaxSize < doubleCapacity) {
      maxCapacity(asg.getAutoScalingGroupName, doubleCapacity)
    }

    desiredCapacity(asg.getAutoScalingGroupName, doubleCapacity)
  }

  lazy val description = "Double the size of the auto-scaling group for package: %s, stage: %s" format (
    packageName, stage.name)
}

case class WaitForStabilization(packageName: String, stage: Stage, duration: Long) extends ASGTask
    with RepeatedPollingCheck {

  def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing) {
    check {
      isStablized(refresh(asg))
    }
  }

  lazy val description: String = "Check the desired number of hosts in ASG are up and in ELB"
}

case class CullInstancesWithTerminationTag(packageName: String, stage: Stage) extends ASGTask {
  def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing) {
    for (instance <- asg.getInstances) {
      if (EC2.hasTag(instance, "Magenta", "Terminate")) {
        cull(asg, instance)
      }
    }
  }

  lazy val description = "Terminate instances with the termination tag for this deploy"
}

trait ASGTask extends Task with ASG {
  def packageName: String
  def stage: Stage

  def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing)

  override def execute(keyRing: KeyRing) {
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