package magenta.tasks

import java.net.ConnectException

import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model.{AutoScalingGroup, Instance}
import magenta.{DeploymentPackage, KeyRing, Stage, _}
import okhttp3._
import org.json4s._
import play.api.libs.json.Json

import scala.collection.JavaConversions._

case class WaitForElasticSearchClusterGreen(pkg: DeploymentPackage, stage: Stage, stack: Stack, duration: Long, region: Region)
                                           (implicit val keyRing: KeyRing)
  extends ASGTask with RepeatedPollingCheck {

  val description = "Wait for the elasticsearch cluster status to be green"
  override val verbose =
    """Minimise thrashing while rebalancing by waiting until the elasticsearch cluster status is green.
      |Requires access to port 9200 on cluster members.
    """.stripMargin

  override def execute(asg: AutoScalingGroup, reporter: DeployReporter, stopFlag: => Boolean, asgClient: AmazonAutoScaling) {
    implicit val ec2Client = EC2.makeEc2Client(keyRing, region)
    val instance = EC2(asg.getInstances.headOption.getOrElse {
      throw new IllegalArgumentException("Auto-scaling group: %s had no instances" format (asg))
    }, ec2Client)
    val node = ElasticSearchNode(instance.getPublicDnsName)
    check(reporter, stopFlag) {
      node.inHealthyClusterOfSize(ASG.refresh(asg, asgClient).getDesiredCapacity)
    }
  }
}

case class CullElasticSearchInstancesWithTerminationTag(pkg: DeploymentPackage, stage: Stage, stack: Stack, duration: Long, region: Region)
                                                       (implicit val keyRing: KeyRing)
  extends ASGTask with RepeatedPollingCheck{

  override def execute(asg: AutoScalingGroup, reporter: DeployReporter, stopFlag: => Boolean, asgClient: AmazonAutoScaling) {
    implicit val ec2Client = EC2.makeEc2Client(keyRing, region)
    implicit val elbClient = ELB.client(keyRing, region)
    val newNode = asg.getInstances.filterNot(EC2.hasTag(_, "Magenta", "Terminate", ec2Client)).head
    val newESNode = ElasticSearchNode(EC2(newNode, ec2Client).getPublicDnsName)

    def cullInstance(instance: Instance) {
        val node = ElasticSearchNode(EC2(instance, ec2Client).getPublicDnsName)
        check(reporter, stopFlag) {
          newESNode.inHealthyClusterOfSize(ASG.refresh(asg, asgClient).getDesiredCapacity)
        }
        if (!stopFlag) {
          node.shutdown()
          check(reporter, stopFlag) {
            newESNode.inHealthyClusterOfSize(ASG.refresh(asg, asgClient).getDesiredCapacity - 1)
          }
        }
        if (!stopFlag) ASG.cull(asg, instance, asgClient, elbClient)
    }

    val instancesToKill = asg.getInstances.filter(instance => EC2.hasTag(instance, "Magenta", "Terminate", ec2Client))
    val orderedInstancesToKill = instancesToKill.transposeBy(_.getAvailabilityZone)
    orderedInstancesToKill.foreach(cullInstance)
  }

  lazy val description = "Terminate instances with the termination tag for this deploy"
}

case class ElasticSearchNode(address: String) {
  implicit val format = DefaultFormats

  val http = new OkHttpClient()
  private def clusterHealth = {
    val request = new Request.Builder()
        .url(
          new HttpUrl.Builder()
            .scheme("http")
            .host(address)
            .port(9200)
            .addPathSegment("_cluster")
            .addPathSegment("health")
            .build()
        )
        .build()
    val response = http.newCall(request).execute()
    Json.parse(response.body().string())
  }

  def dataNodesInCluster = (clusterHealth \ "number_of_data_nodes").as[Int]
  def clusterIsHealthy = (clusterHealth \ "status").as[String] == "green"

  def inHealthyClusterOfSize(desiredClusterSize: Int) =
    try {
      clusterIsHealthy && dataNodesInCluster == desiredClusterSize
    } catch {
      case e: ConnectException => false
    }

  def shutdown() = {
    val request = new Request.Builder()
        .url(new HttpUrl.Builder()
          .scheme("http")
          .host(address)
          .port(9200)
          .addPathSegment("_cluster")
          .addPathSegment("nodes")
          .addPathSegment("_local")
          .addPathSegment("_shutdown")
          .build()
        )
        .post(new FormBody.Builder().build())
        .build()
    http.newCall(request).execute()
  }
}


