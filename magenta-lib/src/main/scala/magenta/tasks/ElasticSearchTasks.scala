package magenta.tasks

import java.net.ConnectException

import magenta.{DeploymentPackage, KeyRing, Stage, _}
import okhttp3._
import org.json4s._
import play.api.libs.json.Json
import software.amazon.awssdk.services.autoscaling.AutoScalingClient
import software.amazon.awssdk.services.autoscaling.model.{AutoScalingGroup, Instance}
import software.amazon.awssdk.services.ec2.Ec2Client

import scala.collection.JavaConverters._

case class WaitForElasticSearchClusterGreen(pkg: DeploymentPackage, stage: Stage, stack: Stack, duration: Long, region: Region)
                                           (implicit val keyRing: KeyRing)
  extends ASGTask with RepeatedPollingCheck {

  val description = "Wait for the elasticsearch cluster status to be green"
  override val verbose =
    """Minimise thrashing while rebalancing by waiting until the elasticsearch cluster status is green.
      |Requires access to port 9200 on cluster members.
    """.stripMargin

  override def execute(asg: AutoScalingGroup, reporter: DeployReporter, stopFlag: => Boolean, asgClient: AutoScalingClient) {
    EC2.withEc2Client(keyRing, region) { ec2Client =>
      val instance = EC2(asg.instances.asScala.headOption.getOrElse {
        throw new IllegalArgumentException(s"Auto-scaling group: $asg had no instances")
      }, ec2Client)
      val node = ElasticSearchNode(instance.publicDnsName)
      check(reporter, stopFlag) {
        node.inHealthyClusterOfSize(ASG.refresh(asg, asgClient).desiredCapacity)
      }
    }
  }
}

case class CullElasticSearchInstancesWithTerminationTag(pkg: DeploymentPackage, stage: Stage, stack: Stack, duration: Long, region: Region)
                                                       (implicit val keyRing: KeyRing)
  extends ASGTask with RepeatedPollingCheck{

  override def execute(asg: AutoScalingGroup, reporter: DeployReporter, stopFlag: => Boolean, asgClient: AutoScalingClient) {
    EC2.withEc2Client(keyRing, region) { ec2Client =>
      ELB.withClient(keyRing, region) { elbClient =>
        val newNode = asg.instances.asScala.filterNot(EC2.hasTag(_, "Magenta", "Terminate", ec2Client)).head
        val newESNode = ElasticSearchNode(EC2(newNode, ec2Client).publicDnsName)

        def cullInstance(instance: Instance) {
          val node = ElasticSearchNode(EC2(instance, ec2Client).publicDnsName)
          check(reporter, stopFlag) {
            newESNode.inHealthyClusterOfSize(ASG.refresh(asg, asgClient).desiredCapacity)
          }
          if (!stopFlag) {
            node.shutdown()
            check(reporter, stopFlag) {
              newESNode.inHealthyClusterOfSize(ASG.refresh(asg, asgClient).desiredCapacity - 1)
            }
          }
          if (!stopFlag) ASG.cull(asg, instance, asgClient, elbClient)
        }

        val instancesToKill = asg.instances.asScala.filter(instance => EC2.hasTag(instance, "Magenta", "Terminate", ec2Client))
        val orderedInstancesToKill = instancesToKill.transposeBy(_.availabilityZone)
        orderedInstancesToKill.foreach(cullInstance)
      }
    }
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
    val result = http.newCall(request).execute()
    result.body().close()
  }
}


