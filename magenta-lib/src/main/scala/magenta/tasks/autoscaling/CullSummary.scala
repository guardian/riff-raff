package magenta.tasks.autoscaling

import magenta.tasks.EC2
import magenta.tasks.autoscaling.CullSummary.isNotAlreadyTerminating
import magenta.{DeployReporter, Loggable}
import software.amazon.awssdk.services.autoscaling.model.{
  AutoScalingGroup,
  Instance,
  LifecycleState
}
import software.amazon.awssdk.services.ec2.Ec2Client

import scala.jdk.CollectionConverters._

object CullSummary extends Loggable {
  // See https://docs.aws.amazon.com/autoscaling/ec2/userguide/lifecycle-hooks.html#lifecycle-hooks-overview
  val TerminatingStates: Set[LifecycleState] = Set(
    LifecycleState.TERMINATING,
    LifecycleState.TERMINATING_WAIT,
    LifecycleState.TERMINATING_PROCEED,
    LifecycleState.TERMINATED
  )

  def isNotAlreadyTerminating(instance: Instance): Boolean =
    !TerminatingStates(instance.lifecycleState)

  def logIfLifecycleStateUnknown(instance: Instance): Unit = if (
    instance.lifecycleState == LifecycleState.UNKNOWN_TO_SDK_VERSION
  )
    logger.warn(
      s"Instance lifecycle state ${instance.lifecycleStateAsString} isn't recognised in the AWS SDK. Is there a later version of the AWS SDK available?"
    )

  def isTaggedForTermination(instance: Instance)(implicit
      ec2Client: Ec2Client
  ): Boolean =
    EC2.hasTag(instance, "Magenta", "Terminate", ec2Client)

  def forAsg(asg: AutoScalingGroup, reporter: DeployReporter)(implicit
      ec2Client: Ec2Client
  ): CullSummary = {
    val allInstances = asg.instances.asScala.toSet

    reporter.verbose(
      s"Found the following instances: ${allInstances.map(_.instanceId).mkString(", ")}"
    )
    allInstances.foreach(logIfLifecycleStateUnknown)

    CullSummary(
      allInstances,
      instancesThatMustTerminate = allInstances.filter(isTaggedForTermination)
    )
  }
}

/** Summarises the instances in an ASG that should be culled - which ones are
  * already terminating, and which ones must be told to terminate.
  */
case class CullSummary(
    allInstances: Set[Instance],
    instancesThatMustTerminate: Set[Instance]
) {
  val instancesToKill: Set[Instance] =
    instancesThatMustTerminate.filter(isNotAlreadyTerminating)

  val isCullComplete: Boolean = instancesThatMustTerminate.isEmpty
}
