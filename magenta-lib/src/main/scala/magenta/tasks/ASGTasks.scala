package magenta.tasks

import magenta.{MessageBroker, Stage, KeyRing}
import com.amazonaws.services.autoscaling.model.{Instance, AutoScalingGroup}
import dispatch.{Http, :/}
import collection.JavaConversions._

case class DoubleSize(packageName: String, stage: Stage) extends ASGTask {
  def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing) {
    val doubleCapacity = asg.getDesiredCapacity * 2
    if (asg.getMaxSize < doubleCapacity)
      maxCapacity(asg.getAutoScalingGroupName, doubleCapacity)

    desiredCapacity(asg.getAutoScalingGroupName, doubleCapacity)
  }

  lazy val description = "Double the size of the auto-scaling group for package: %s, stage: %s" format (
    packageName, stage.name)
}

case class WaitTillUpAndInELB(packageName: String, stage: Stage, duration: Long) extends ASGTask
    with RepeatedPollingCheck {

  def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing) {
    check {
      val updatedAsg = refresh(asg)
      atDesiredCapacity(updatedAsg) && allInELB(updatedAsg)
    }
  }

  def description: String = "Check all hosts in an ASG are up and in ELB"
}

case class CullInstancesWithoutVersion(packageName: String, stage: Stage, desiredVersion: String) extends ASGTask {


  override def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing) {
    for (instance <- asg.getInstances) {
      if (versionOf(instance) != desiredVersion) {
        cull(asg, instance)
      }
    }

    def versionOf(instance: Instance) = Http(:/ (EC2(instance).getPublicDnsName, 9000) / "management/manifest" >- {
      body => {
        def keyValMap(properties: String): Map[String, String] = body.lines.map(_.split(':').map(_.trim)).collect
          { case Array(k, v) => k -> v }.toMap

        keyValMap(body)("Build")
      }
    })
  }

  def description: String = "Terminate instances without the version specified for this deploy"
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