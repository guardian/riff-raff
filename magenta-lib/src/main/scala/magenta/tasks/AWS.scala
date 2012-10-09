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

trait S3 extends AWS {
  def s3client(keyRing: KeyRing) = new AmazonS3Client(credentials(keyRing))

  def putObjectRequestWithPublicRead(bucket: String, key: String, file: File, cacheControlHeader: Option[String]) = {
    val metaData = new ObjectMetadata
    cacheControlHeader foreach { metaData.setCacheControl(_) }
    new PutObjectRequest(bucket, key, file).withCannedAcl(PublicRead).withMetadata(metaData)
  }
}

trait ASG extends AWS {
  def asgClient(implicit keyRing: KeyRing) = {
    val client = new AmazonAutoScalingClient(credentials(keyRing))
    client.setEndpoint("autoscaling.eu-west-1.amazonaws.com")
    client
  }

  def desiredCapacity(name: String, capacity: Int)(implicit keyRing: KeyRing) =
    asgClient.setDesiredCapacity(
      new SetDesiredCapacityRequest().withAutoScalingGroupName(name).withDesiredCapacity(capacity)
    )

  def maxCapacity(name: String, capacity: Int)(implicit keyRing: KeyRing) =
    asgClient.updateAutoScalingGroup(
      new UpdateAutoScalingGroupRequest().withAutoScalingGroupName(name).withMaxSize(capacity))

  def apply(name: String)(implicit keyRing: KeyRing) =
    asgClient.describeAutoScalingGroups(
      new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(name)
    ).getAutoScalingGroups.head

  def withPackageAndStage(packageName: String, stage: Stage)(implicit keyRing: KeyRing): AutoScalingGroup = {
    def hasTags(keyValues: (String,String)*) = (asg: AutoScalingGroup) => {
      keyValues.forall { case (key, value) =>
        asg.getTags exists { tag =>
          tag.getKey == key && tag.getValue == value
        }
      }
    }

    asgClient.describeAutoScalingGroups().getAutoScalingGroups.toList.filter {
      hasTags(("Stage" -> stage.name),("App" -> packageName))
    }.headOption.getOrElse(
      throw new NoKnownAutoScalingGroupException(packageName, stage))
  }
}
object ASG extends ASG

class NoKnownAutoScalingGroupException(pkgName: String, stage: Stage) extends Exception(
  "No autoscaling group found with tags: App -> %s, Stage -> %s" format (pkgName, stage.name)
)

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
