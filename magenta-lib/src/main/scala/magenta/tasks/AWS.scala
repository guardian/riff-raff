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
import com.amazonaws.services.elasticloadbalancing.model.{DescribeInstanceHealthRequest, LoadBalancerDescription, DescribeLoadBalancersRequest}

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

  def atDesiredCapacity(asg: AutoScalingGroup) =
    asg.getDesiredCapacity == asg.getInstances.size

  def allInELB(asg: AutoScalingGroup)(implicit keyRing: KeyRing) = {
    val elbName = asg.getLoadBalancerNames.head
    val elb = ELB(elbName).get
    ELB.instanceHealth(elb).forall( instance => instance.getState == "InService")
  }

  def refresh(asg: AutoScalingGroup)(implicit keyRing: KeyRing) =
    client.describeAutoScalingGroups(
      new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(asg.getAutoScalingGroupName)
    ).getAutoScalingGroups.head

  def withPackageAndStage(packageName: String, stage: Stage)(implicit keyRing: KeyRing): Option[AutoScalingGroup] = {
    def hasTags(keyValues: (String,String)*) = (asg: AutoScalingGroup) => {
      keyValues.forall { case (key, value) =>
        asg.getTags exists { tag =>
          tag.getKey == key && tag.getValue == value
        }
      }
    }

    client.describeAutoScalingGroups().getAutoScalingGroups.toList.filter {
      hasTags(("Stage" -> stage.name),("App" -> packageName))
    }.headOption
  }
}

trait ELB extends AWS {
  def client(implicit keyRing: KeyRing) = {
    val client = new AmazonElasticLoadBalancingClient(credentials(keyRing))
    client.setEndpoint("autoscaling.eu-west-1.amazonaws.com")
    client
  }

  def instanceHealth(elb: LoadBalancerDescription)(implicit keyRing: KeyRing) =
    client.describeInstanceHealth(new DescribeInstanceHealthRequest(elb.getLoadBalancerName)).getInstanceStates

  def apply(name: String)(implicit keyRing: KeyRing) =
    client.describeLoadBalancers(new DescribeLoadBalancersRequest().withLoadBalancerNames(name))
      .getLoadBalancerDescriptions.headOption
}

object ELB extends ELB

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
