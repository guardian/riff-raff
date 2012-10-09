package magenta.tasks

import magenta.{Stage, KeyRing}

case class DoubleSize(packageName: String, stage: Stage) extends Task with ASG {
  def execute(keyRing: KeyRing) {
    implicit val key = keyRing
    val asg = withPackageAndStage(packageName, stage)

    val doubleCapacity = asg.getDesiredCapacity * 2
    if (asg.getMaxSize < doubleCapacity)
      maxCapacity(asg.getAutoScalingGroupName, doubleCapacity)

    desiredCapacity(asg.getAutoScalingGroupName, doubleCapacity)
  }

  lazy val description = "Double the size of the auto-scaling group for package: %s, stage: %s" format (
    packageName, stage.name)

  def verbose = description

  val taskHosts = Nil
}