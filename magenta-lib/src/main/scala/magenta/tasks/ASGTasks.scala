package magenta.tasks

import magenta.deployment_type.AutoScalingGroupInfo
import magenta.tasks.EC2.withEc2Client
import magenta.tasks.autoscaling.CullSummary
import magenta.{KeyRing, _}
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.autoscaling.AutoScalingClient
import software.amazon.awssdk.services.autoscaling.model.{
  AutoScalingGroup,
  Instance,
  LifecycleState,
  SetInstanceProtectionRequest
}
import software.amazon.awssdk.services.ec2.Ec2Client

import java.time.Duration
import scala.jdk.CollectionConverters._

case class CheckGroupSize(info: AutoScalingGroupInfo, region: Region)(implicit
    val keyRing: KeyRing
) extends ASGTask {
  override def execute(
      asg: AutoScalingGroup,
      resources: DeploymentResources,
      stopFlag: => Boolean
  )(implicit asgClient: AutoScalingClient): Unit = {
    val doubleCapacity = asg.desiredCapacity * 2
    resources.reporter.verbose(
      s"ASG desired = ${asg.desiredCapacity}; ASG max = ${asg.maxSize}; Target = $doubleCapacity"
    )
    if (asg.maxSize < doubleCapacity) {
      resources.reporter.fail(
        s"Autoscaling group does not have the capacity to deploy current max = ${asg.maxSize} - desired max = $doubleCapacity"
      )
    }
  }

  lazy val description =
    s"Checking there is enough capacity in ASG $asgName to deploy"
}

case class TagCurrentInstancesWithTerminationTag(
    info: AutoScalingGroupInfo,
    region: Region
)(implicit val keyRing: KeyRing)
    extends ASGTask {
  override def execute(
      asg: AutoScalingGroup,
      resources: DeploymentResources,
      stopFlag: => Boolean
  )(implicit asgClient: AutoScalingClient): Unit = {
    if (asg.instances.asScala.nonEmpty) {
      withEc2Client(keyRing, region, resources) { ec2Client =>
        resources.reporter.verbose(
          s"Tagging ${asg.instances.asScala.toList.map(_.instanceId).mkString(", ")}"
        )
        EC2.setTag(
          asg.instances.asScala.toList,
          "Magenta",
          "Terminate",
          ec2Client
        )
      }
    } else {
      resources.reporter.verbose(s"No instances to tag")
    }
  }

  lazy val description =
    s"Tag existing instances of the auto-scaling group $asgName for termination"
}

case class ProtectCurrentInstances(info: AutoScalingGroupInfo, region: Region)(
    implicit val keyRing: KeyRing
) extends ASGTask {
  override def execute(
      asg: AutoScalingGroup,
      resources: DeploymentResources,
      stopFlag: => Boolean
  )(implicit asgClient: AutoScalingClient): Unit = {
    val instances = asg.instances.asScala.toList
    val instancesInService =
      instances.filter(_.lifecycleState == LifecycleState.IN_SERVICE)
    if (instancesInService.nonEmpty) {
      val instanceIds = instancesInService.map(_.instanceId)
      val request = SetInstanceProtectionRequest
        .builder()
        .autoScalingGroupName(asg.autoScalingGroupName)
        .instanceIds(instanceIds: _*)
        .protectedFromScaleIn(true)
        .build()
      asgClient.setInstanceProtection(request)
    } else {
      resources.reporter.verbose(s"No instances to protect")
    }
  }

  lazy val description =
    s"Protect existing instances in group $asgName against scale in events"
}

case class DoubleSize(info: AutoScalingGroupInfo, region: Region)(implicit
    val keyRing: KeyRing
) extends ASGTask {

  override def execute(
      asg: AutoScalingGroup,
      resources: DeploymentResources,
      stopFlag: => Boolean
  )(implicit asgClient: AutoScalingClient): Unit = {
    val targetCapacity = asg.desiredCapacity * 2
    resources.reporter.verbose(s"Doubling capacity to $targetCapacity")
    ASG.desiredCapacity(asg.autoScalingGroupName, targetCapacity, asgClient)
  }

  lazy val description =
    s"Double the size of the auto-scaling group called $asgName"
}

sealed abstract class Pause(duration: Duration)(implicit val keyRing: KeyRing)
    extends ASGTask {
  val purpose: String

  def description: String = s"Wait extra ${duration.toMillis}ms to $purpose"

  def execute(
      asg: AutoScalingGroup,
      resources: DeploymentResources,
      stopFlag: => Boolean
  )(implicit asgClient: AutoScalingClient): Unit = {
    if (asg.desiredCapacity == 0 && asg.instances.isEmpty)
      resources.reporter.verbose(
        "Skipping pause as there are no instances and desired capacity is zero"
      )
    else
      Thread.sleep(duration.toMillis)
  }
}

case class HealthcheckGrace(
    info: AutoScalingGroupInfo,
    region: Region,
    duration: Duration
)(implicit keyRing: KeyRing)
    extends Pause(duration) {
  val purpose: String = "let Load Balancer report correctly"
}

case class WarmupGrace(
    info: AutoScalingGroupInfo,
    region: Region,
    duration: Duration
)(implicit keyRing: KeyRing)
    extends Pause(duration) {
  val purpose: String = "let instances in Load Balancer warm up"
}

case class TerminationGrace(
    info: AutoScalingGroupInfo,
    region: Region,
    duration: Duration
)(implicit keyRing: KeyRing)
    extends Pause(duration) {
  val purpose: String = "let Load Balancer report correctly"
}

case class WaitForStabilization(
    info: AutoScalingGroupInfo,
    duration: Duration,
    region: Region
)(implicit val keyRing: KeyRing)
    extends ASGTask
    with SlowRepeatedPollingCheck {

  override def execute(
      asg: AutoScalingGroup,
      resources: DeploymentResources,
      stopFlag: => Boolean
  )(implicit asgClient: AutoScalingClient): Unit = {
    ELB.withClient(keyRing, region, resources) { elbClient =>
      check(resources.reporter, stopFlag) {
        try {
          ASG.isStabilized(ASG.refresh(asg), elbClient) match {
            case Left(reason) =>
              resources.reporter.verbose(reason)
              false
            case Right(()) => true
          }
        } catch {
          case e: AwsServiceException if isRateExceeded(e) =>
            resources.reporter.info(e.getMessage)
            false
        }
      }
    }

    // found this out by good old trial and error
    def isRateExceeded(e: AwsServiceException) =
      e.statusCode == 400 && e.isThrottlingException
  }

  lazy val description: String =
    s"Check the desired number of hosts in both the ASG ($asgName) and ELB are up and that the number of hosts match"
}

case class CullInstancesWithTerminationTag(
    info: AutoScalingGroupInfo,
    region: Region
)(implicit val keyRing: KeyRing)
    extends ASGTask {
  override def execute(
      asg: AutoScalingGroup,
      resources: DeploymentResources,
      stopFlag: => Boolean
  )(implicit asgClient: AutoScalingClient): Unit = {
    withEc2Client(keyRing, region, resources) { implicit ec2Client =>
      val instancesToKill =
        prepareOrderedInstancesToKill(asg, resources.reporter)
      ELB.withClient(keyRing, region, resources) { implicit elbClient =>
        try {
          instancesToKill.foreach(instance => ASG.cull(asg, instance))
        } catch {
          case e: AwsServiceException if desiredSizeReset(e) =>
            resources.reporter.warning(
              "Your ASG desired size may have been reset. This may be because two parts of the deploy are attempting to modify a cloudformation stack simultaneously. Please check that appropriate dependencies are included in riff-raff.yaml, or ensure desiredSize isn't set in the Cloudformation."
            )
            throw new ASGResetException(
              s"Your ASG desired size may have been reset ${e.getMessage}",
              e
            )
        }
      }
    }

    def desiredSizeReset(e: AwsServiceException) = e.statusCode == 400 && e
      .awsErrorDetails()
      .toString
      .contains("ValidationError")
  }

  private def prepareOrderedInstancesToKill(
      asg: AutoScalingGroup,
      reporter: DeployReporter
  )(implicit ec2Client: Ec2Client): Seq[Instance] = {
    val cullSummary = CullSummary.forAsg(asg, reporter)
    reportOnRetainedInstances(reporter, cullSummary)
    val orderedInstancesToKill =
      cullSummary.instancesToKill.toSeq.transposeBy(_.availabilityZone)
    reporter.verbose(
      s"Culling instances: ${orderedInstancesToKill.map(_.instanceId).mkString(", ")}"
    )
    orderedInstancesToKill
  }

  private def reportOnRetainedInstances(
      reporter: DeployReporter,
      cullSummary: CullSummary
  )(implicit ec2Client: Ec2Client): Unit = {
    val instancesToKill = cullSummary.instancesToKill
    val instancesToRetain =
      cullSummary.allInstances.diff(instancesToKill).toList
    reporter.verbose(
      s"Decided to keep the following instances: ${instancesToRetain.map(_.instanceId).mkString(", ")}"
    )

    if (instancesToRetain.size != instancesToKill.size) {
      reporter.warning(
        s"Terminating ${instancesToKill.size} instances and retaining ${instancesToRetain.size} instances"
      )
      logger.warn(
        s"Unusual number of instances terminated as part of autoscaling deployment"
      )
      instancesToRetain.foreach(instanceToRetain => {
        val tags = EC2
          .allTags(instanceToRetain, ec2Client)
          .map(tag => s"${tag.key}:${tag.value}")
          .mkString(", ")
        reporter.verbose(
          s"Will not terminate $instanceToRetain. State: ${instanceToRetain.lifecycleStateAsString}. Tags: $tags"
        )
      })
    }
  }

  lazy val description =
    s"Request termination for instances in $asgName with the termination tag for this deploy"
}

case class WaitForCullToComplete(
    info: AutoScalingGroupInfo,
    duration: Duration,
    region: Region
)(implicit val keyRing: KeyRing)
    extends ASGTask
    with SlowRepeatedPollingCheck {

  override def execute(
      asg: AutoScalingGroup,
      resources: DeploymentResources,
      stopFlag: => Boolean
  )(implicit asgClient: AutoScalingClient): Unit =
    withEc2Client(keyRing, region, resources) { implicit ec2Client =>
      check(resources.reporter, stopFlag) {
        CullSummary
          .forAsg(ASG.refresh(asg), resources.reporter)
          .isCullComplete
      }
    }

  lazy val description: String =
    s"Check that all instances tagged for termination in $asgName have been terminated"
}

case class SuspendAlarmNotifications(
    info: AutoScalingGroupInfo,
    region: Region
)(implicit val keyRing: KeyRing)
    extends ASGTask {

  override def execute(
      asg: AutoScalingGroup,
      resources: DeploymentResources,
      stopFlag: => Boolean
  )(implicit asgClient: AutoScalingClient): Unit = {
    ASG.suspendAlarmNotifications(asg.autoScalingGroupName, asgClient)
  }

  lazy val description =
    s"Suspending Alarm Notifications - $asgName will no longer scale on any configured alarms"
}

case class ResumeAlarmNotifications(info: AutoScalingGroupInfo, region: Region)(
    implicit val keyRing: KeyRing
) extends ASGTask {

  override def execute(
      asg: AutoScalingGroup,
      resources: DeploymentResources,
      stopFlag: => Boolean
  )(implicit asgClient: AutoScalingClient): Unit =
    ASG.resumeAlarmNotifications(asg.autoScalingGroupName, asgClient)

  lazy val description =
    s"Resuming Alarm Notifications - $asgName will scale on any configured alarms"
}

class ASGResetException(message: String, throwable: Throwable)
    extends Throwable(message, throwable)

trait ASGTask extends Task {
  def info: AutoScalingGroupInfo
  def asgName: String = info.asg.autoScalingGroupName
  def region: Region

  def execute(
      asg: AutoScalingGroup,
      resources: DeploymentResources,
      stopFlag: => Boolean
  )(implicit asgClient: AutoScalingClient): Unit

  override def execute(
      resources: DeploymentResources,
      stopFlag: => Boolean
  ): Unit = {
    ASG.withAsgClient(keyRing, region, resources) { implicit asgClient =>
      resources.reporter.verbose(
        s"Looked up group matching tags ${info.tagRequirements}; identified ${info.asg.autoScalingGroupARN}"
      )
      // Although we have already identified the correct autoscaling group, we always need to check the latest state
      // before performing a task.
      // For example, we need to make sure that we have the latest desired capacity when doubling the size of the ASG.
      val latestAsgState =
        ASG.getGroupByName(asgName, asgClient, resources.reporter)
      execute(latestAsgState, resources, stopFlag)
    }
  }
}
