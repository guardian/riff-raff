package magenta.tasks

import magenta.{KeyRing, _}
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.autoscaling.AutoScalingClient
import software.amazon.awssdk.services.autoscaling.model.{AutoScalingGroup, LifecycleState, SetInstanceProtectionRequest}
import software.amazon.awssdk.services.ec2.Ec2Client

import scala.collection.JavaConverters._

case class CheckGroupSize(asgName: String, region: Region)(implicit val keyRing: KeyRing) extends ASGTask {
  override def execute(asg: AutoScalingGroup, resources: DeploymentResources, stopFlag: => Boolean, asgClient: AutoScalingClient) {
    val doubleCapacity = asg.desiredCapacity * 2
    resources.reporter.verbose(s"ASG desired = ${asg.desiredCapacity}; ASG max = ${asg.maxSize}; Target = $doubleCapacity")
    if (asg.maxSize < doubleCapacity) {
      resources.reporter.fail(
        s"Autoscaling group does not have the capacity to deploy current max = ${asg.maxSize} - desired max = $doubleCapacity"
      )
    }
  }

  lazy val description = "Checking there is enough capacity to deploy"
}

case class TagCurrentInstancesWithTerminationTag(asgName: String, region: Region)(implicit val keyRing: KeyRing) extends ASGTask {
  override def execute(asg: AutoScalingGroup, resources: DeploymentResources, stopFlag: => Boolean, asgClient: AutoScalingClient) {
    if (asg.instances.asScala.nonEmpty) {
      EC2.withEc2Client(keyRing, region, resources) { ec2Client =>
        resources.reporter.verbose(s"Tagging ${asg.instances.asScala.toList.map(_.instanceId).mkString(", ")}")
        EC2.setTag(asg.instances.asScala.toList, "Magenta", "Terminate", ec2Client)
      }
    } else {
      resources.reporter.verbose(s"No instances to tag")
    }
  }

  lazy val description = "Tag existing instances of the auto-scaling group for termination"
}

case class ProtectCurrentInstances(asgName: String, region: Region)(implicit val keyRing: KeyRing) extends ASGTask {
  override def execute(asg: AutoScalingGroup, resources: DeploymentResources, stopFlag: => Boolean, asgClient: AutoScalingClient) {
    val instances = asg.instances.asScala.toList
    val instancesInService = instances.filter(_.lifecycleState == LifecycleState.IN_SERVICE)
    if (instancesInService.nonEmpty) {
      val instanceIds = instancesInService.map(_.instanceId)
      val request = SetInstanceProtectionRequest.builder()
        .autoScalingGroupName(asg.autoScalingGroupName)
        .instanceIds(instanceIds: _*)
        .protectedFromScaleIn(true)
        .build()
      asgClient.setInstanceProtection(request)
    } else {
      resources.reporter.verbose(s"No instances to protect")
    }
  }

  lazy val description = "Protect existing instances against scale in events"
}

case class DoubleSize(asgName: String, region: Region)(implicit val keyRing: KeyRing) extends ASGTask {

  override def execute(asg: AutoScalingGroup, resources: DeploymentResources, stopFlag: => Boolean, asgClient: AutoScalingClient) {
    val targetCapacity = asg.desiredCapacity * 2
    resources.reporter.verbose(s"Doubling capacity to $targetCapacity")
    ASG.desiredCapacity(asg.autoScalingGroupName, targetCapacity, asgClient)
  }

  lazy val description = s"Double the size of the auto-scaling group called $asgName"
}

sealed abstract class Pause(durationMillis: Long)(implicit val keyRing: KeyRing) extends ASGTask {
  def execute(asg: AutoScalingGroup, resources: DeploymentResources, stopFlag: => Boolean, asgClient: AutoScalingClient): Unit = {
    if (asg.desiredCapacity == 0 && asg.instances.isEmpty)
      resources.reporter.verbose("Skipping pause as there are no instances and desired capacity is zero")
    else
      Thread.sleep(durationMillis)
  }
}

case class HealthcheckGrace(asgName: String, region: Region, durationMillis: Long)(implicit keyRing: KeyRing) extends Pause(durationMillis) {
  def description: String = s"Wait extra ${durationMillis}ms to let Load Balancer report correctly"
}

case class WarmupGrace(asgName: String, region: Region, durationMillis: Long)(implicit keyRing: KeyRing) extends Pause(durationMillis) {
  def description: String = s"Wait extra ${durationMillis}ms to let instances in Load Balancer warm up"
}

case class TerminationGrace(asgName: String, region: Region, durationMillis: Long)(implicit keyRing: KeyRing) extends Pause(durationMillis) {
  def description: String = s"Wait extra ${durationMillis}ms to let Load Balancer report correctly"
}

case class WaitForStabilization(asgName: String, duration: Long, region: Region)(implicit val keyRing: KeyRing) extends ASGTask
    with SlowRepeatedPollingCheck {

  override def execute(asg: AutoScalingGroup, resources: DeploymentResources, stopFlag: => Boolean, asgClient: AutoScalingClient) {
    ELB.withClient(keyRing, region, resources) { elbClient =>
      check(resources.reporter, stopFlag) {
        try {
          ASG.isStabilized(ASG.refresh(asg, asgClient), elbClient) match {
            case Left(reason) =>
              resources.reporter.verbose(reason)
              false
            case Right(()) => true
          }
        } catch {
          case e: AwsServiceException if isRateExceeded(e) => {
            resources.reporter.info(e.getMessage)
            false
          }
        }
      }
    }

    //found this out by good old trial and error
    def isRateExceeded(e: AwsServiceException) = e.statusCode == 400 && e.isThrottlingException
  }

  lazy val description: String = "Check the desired number of hosts in both the ASG and ELB are up and that the number of hosts match"
}

case class CullInstancesWithTerminationTag(asgName: String, region: Region)(implicit val keyRing: KeyRing) extends ASGTask {
  override def execute(asg: AutoScalingGroup, resources: DeploymentResources, stopFlag: => Boolean, asgClient: AutoScalingClient) {
    EC2.withEc2Client(keyRing, region, resources) { ec2Client =>
      ELB.withClient(keyRing, region, resources) { elbClient =>
        val instancesToKill = asg.instances.asScala.filter(instance => EC2.hasTag(instance, "Magenta", "Terminate", ec2Client))
        val orderedInstancesToKill = instancesToKill.transposeBy(_.availabilityZone)
        try {
          resources.reporter.verbose(s"Culling instances: ${orderedInstancesToKill.map(_.instanceId).mkString(", ")}")
          orderedInstancesToKill.foreach(instance => ASG.cull(asg, instance, asgClient, elbClient))
        } catch {
          case e: AwsServiceException if desiredSizeReset(e) =>
            resources.reporter.warning("Your ASG desired size may have been reset. This may be because two parts of the deploy are attempting to modify a cloudformation stack simultaneously. Please check that appropriate dependencies are included in riff-raff.yaml, or ensure desiredSize isn't set in the Cloudformation.")
            throw new ASGResetException(s"Your ASG desired size may have been reset ${e.getMessage}", e)
        }
      }
    }

    def desiredSizeReset(e: AwsServiceException) = e.statusCode == 400 && e.awsErrorDetails().toString.contains("ValidationError")
  }

  lazy val description = "Terminate instances with the termination tag for this deploy"
}

case class SuspendAlarmNotifications(asgName: String, region: Region)(implicit val keyRing: KeyRing) extends ASGTask {

  override def execute(asg: AutoScalingGroup, resources: DeploymentResources, stopFlag: => Boolean, asgClient: AutoScalingClient) {
    ASG.suspendAlarmNotifications(asg.autoScalingGroupName, asgClient)
  }

  lazy val description = "Suspending Alarm Notifications - group will no longer scale on any configured alarms"
}

case class ResumeAlarmNotifications(asgName: String, region: Region)(implicit val keyRing: KeyRing) extends ASGTask {

  override def execute(asg: AutoScalingGroup, resources: DeploymentResources, stopFlag: => Boolean, asgClient: AutoScalingClient) {
    ASG.resumeAlarmNotifications(asg.autoScalingGroupName, asgClient)
  }

  lazy val description = "Resuming Alarm Notifications - group will scale on any configured alarms"
}

class ASGResetException(message: String, throwable: Throwable) extends Throwable(message, throwable)

trait ASGTask extends Task {
  def asgName: String
  def region: Region

  def execute(asg: AutoScalingGroup, resources: DeploymentResources, stopFlag: => Boolean, asgClient: AutoScalingClient)

  override def execute(resources: DeploymentResources, stopFlag: => Boolean) {
    ASG.withAsgClient(keyRing, region, resources) { asgClient =>
      val group = ASG.getGroupByName(asgName, asgClient, resources.reporter)
      execute(group, resources, stopFlag, asgClient)
    }
  }
}
