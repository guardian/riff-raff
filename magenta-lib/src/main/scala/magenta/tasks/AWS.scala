package magenta.tasks

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import java.io.File
import com.amazonaws.services.s3.model.{PutObjectRequest, ObjectMetadata}
import com.amazonaws.services.s3.model.CannedAccessControlList._
import magenta.{Stage, KeyRing}
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import com.amazonaws.services.autoscaling.model._

import collection.JavaConversions._
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancing.model.{Instance => ELBInstance, DescribeLoadBalancersRequest, DeregisterInstancesFromLoadBalancerRequest, LoadBalancerDescription, DescribeInstanceHealthRequest}
import com.amazonaws.services.autoscaling.model.{Instance => ASGInstance}
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.DescribeInstancesRequest

trait S3 extends AWS {
  def s3client(keyRing: KeyRing) = new AmazonS3Client(credentials(keyRing))

  def putObjectRequestWithPublicRead(bucket: String, key: String, file: File, cacheControlHeader: Option[String]) = {
    val metaData = new ObjectMetadata
    cacheControlHeader foreach { metaData.setCacheControl(_) }
    new PutObjectRequest(bucket, key, file).withCannedAcl(PublicRead).withMetadata(metaData)
  }
}

trait ASG extends AWS {
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

  def allInELB(asg: AutoScalingGroup)(implicit keyRing: KeyRing) = {
    val elbHealth = ELB.instanceHealth(elbName(asg))
    elbHealth.size == asg.getDesiredCapacity && elbHealth.forall( instance => instance.getState == "InService")
  }

  def elbName(asg: AutoScalingGroup) = asg.getLoadBalancerNames.head

  def cull(asg: AutoScalingGroup, instance: Instance)(implicit keyRing: KeyRing) = {
     ELB.deregister(elbName(asg), instance)

    client.terminateInstanceInAutoScalingGroup(
      new TerminateInstanceInAutoScalingGroupRequest()
        .withInstanceId(instance.getInstanceId).withShouldDecrementDesiredCapacity(true)
    )
  }

  def refresh(asg: AutoScalingGroup)(implicit keyRing: KeyRing) =
    client.describeAutoScalingGroups(
      new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(asg.getAutoScalingGroupName)
    ).getAutoScalingGroups.head

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

  def deregister(elbName: String, instance: Instance)(implicit keyRing: KeyRing) =
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
}

object EC2 extends EC2 {
  def apply(instance: Instance)(implicit keyRing: KeyRing) = client.describeInstances(
    new DescribeInstancesRequest().withInstanceIds(instance.getInstanceId)).getReservations.flatMap(_.getInstances).head
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
    keyRing.s3Credentials.map{ c => new BasicAWSCredentials(c.accessKey,c.secretAccessKey) }.getOrElse{ envCredentials }
  }
}
