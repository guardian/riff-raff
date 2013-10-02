package magenta.tasks

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model.{ Instance => ASGInstance, _ }
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.{ Tag => EC2Tag, _ }
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.{ Instance => ELBInstance, _ }
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.CannedAccessControlList.PublicRead
import com.amazonaws.services.s3.model.{ ObjectMetadata, PutObjectRequest }
import java.io.File
import magenta.{Stage, KeyRing}
import scala.collection.JavaConversions._

trait S3 extends AWS {
  def s3client(keyRing: KeyRing) = new AmazonS3Client(credentials(keyRing))

  def putObjectRequestWithPublicRead(bucket: String, key: String, file: File, cacheControlHeader: Option[String]) = {
    val metaData = new ObjectMetadata
    cacheControlHeader foreach { metaData.setCacheControl(_) }
    new PutObjectRequest(bucket, key, file).withCannedAcl(PublicRead).withMetadata(metaData)
  }
}

trait ASG extends AWS {
  def elb: ELB = ELB

  def client(implicit keyRing: KeyRing) = {
    val client = new AmazonAutoScalingClient(credentials(keyRing))
    client.setEndpoint("autoscaling.eu-west-1.amazonaws.com")
    client
  }

  def desiredCapacity(name: String, capacity: Int)(implicit keyRing: KeyRing) =
    client.setDesiredCapacity(
      new SetDesiredCapacityRequest().withAutoScalingGroupName(name).withDesiredCapacity(capacity)
    )

  def maxCapacity(name: String, capacity: Int)(implicit keyRing: KeyRing) =
    client.updateAutoScalingGroup(
      new UpdateAutoScalingGroupRequest().withAutoScalingGroupName(name).withMaxSize(capacity))

  def isStabilized(asg: AutoScalingGroup)(implicit keyRing: KeyRing) = {
    elbName(asg) match {
      case Some(name) => {
        val elbHealth = elb.instanceHealth(name)
        elbHealth.size == asg.getDesiredCapacity && elbHealth.forall( instance => instance.getState == "InService")
      }
      case None => {
        val instances = asg.getInstances
        instances.size == asg.getDesiredCapacity &&
          instances.forall(instance => instance.getLifecycleState == LifecycleState.InService.toString)
      }
    }
  }

  def elbName(asg: AutoScalingGroup) = asg.getLoadBalancerNames.headOption

  def cull(asg: AutoScalingGroup, instance: ASGInstance)(implicit keyRing: KeyRing) = {
    elbName(asg) foreach (ELB.deregister(_, instance))

    client.terminateInstanceInAutoScalingGroup(
      new TerminateInstanceInAutoScalingGroupRequest()
        .withInstanceId(instance.getInstanceId).withShouldDecrementDesiredCapacity(true)
    )
  }

  def refresh(asg: AutoScalingGroup)(implicit keyRing: KeyRing) =
    client.describeAutoScalingGroups(
      new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(asg.getAutoScalingGroupName)
    ).getAutoScalingGroups.head

  def suspendAlarmNotifications(name: String)(implicit keyRing: KeyRing) = client.suspendProcesses(
    new SuspendProcessesRequest().withAutoScalingGroupName(name).withScalingProcesses("AlarmNotification")
  )

  def resumeAlarmNotifications(name: String)(implicit keyRing: KeyRing) = client.resumeProcesses(
    new ResumeProcessesRequest().withAutoScalingGroupName(name).withScalingProcesses("AlarmNotification")
  )

  def withPackageAndStage(packageName: String, stage: Stage)(implicit keyRing: KeyRing): Option[AutoScalingGroup] = {
    implicit def autoscalingGroup2HasTag(asg: AutoScalingGroup) = new {
      def hasTag(key: String, value: String) = asg.getTags exists { tag =>
          tag.getKey == key && tag.getValue == value
        }
    }

    val groups = client.describeAutoScalingGroups().getAutoScalingGroups.toList
    val filteredByPackageAndStage = groups filter {
      _.hasTag("Stage", stage.name)
    } filter { group =>
      group.hasTag("App", packageName) || group.hasTag("Role", packageName)
    }

    filteredByPackageAndStage.headOption
  }
}

trait ELB extends AWS {
  def client(implicit keyRing: KeyRing) = {
    val client = new AmazonElasticLoadBalancingClient(credentials(keyRing))
    client.setEndpoint("elasticloadbalancing.eu-west-1.amazonaws.com")
    client
  }

  def instanceHealth(elbName: String)(implicit keyRing: KeyRing) =
    client.describeInstanceHealth(new DescribeInstanceHealthRequest(elbName)).getInstanceStates

  def deregister(elbName: String, instance: ASGInstance)(implicit keyRing: KeyRing) =
    client.deregisterInstancesFromLoadBalancer(
      new DeregisterInstancesFromLoadBalancerRequest().withLoadBalancerName(elbName)
        .withInstances(new ELBInstance().withInstanceId(instance.getInstanceId)))
}

object ELB extends ELB

trait EC2 extends AWS {
  def client(implicit keyRing: KeyRing) = {
    val client = new AmazonEC2Client(credentials(keyRing))
    client.setEndpoint("ec2.eu-west-1.amazonaws.com")
    client
  }

  def setTag(instances: List[ASGInstance], key: String, value: String)(implicit keyRing: KeyRing) {
    val request = new CreateTagsRequest().
      withResources(instances map { _.getInstanceId }).
      withTags(new EC2Tag(key, value))

    client.createTags(request)
  }

  def hasTag(instance: ASGInstance, key: String, value: String)(implicit keyRing: KeyRing): Boolean = {
    describe(instance).getTags() exists { tag =>
      tag.getKey() == key && tag.getValue() == value
    }
  }

  def describe(instance: ASGInstance)(implicit keyRing: KeyRing) = client.describeInstances(
    new DescribeInstancesRequest().withInstanceIds(instance.getInstanceId)).getReservations.flatMap(_.getInstances).head

  def setElasticIPOfInstance(instanceId: String, elasticIP: String)(implicit keyRing: KeyRing) = {
    client.associateAddress(new AssociateAddressRequest(instanceId, elasticIP))
  }
}

object EC2 extends EC2 {
  def apply(instance: ASGInstance)(implicit keyRing: KeyRing) = describe(instance)
}

trait AWS {
  lazy val accessKey = Option(System.getenv.get("aws_access_key")).getOrElse{
    sys.error("Cannot authenticate, 'aws_access_key' must be set as a system property")
  }
  lazy val secretAccessKey = Option(System.getenv.get("aws_secret_access_key")).getOrElse{
    sys.error("Cannot authenticate, aws_secret_access_key' must be set as a system property")
  }

  lazy val envCredentials = new BasicAWSCredentials(accessKey, secretAccessKey)

  def credentials(keyRing: KeyRing): BasicAWSCredentials = {
    keyRing.apiCredentials.get("aws").map{ credentials => new BasicAWSCredentials(credentials.id,credentials.secret) }.getOrElse{ envCredentials }
  }
}
