package magenta.tasks

import magenta.{KeyRing, Stage}
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import collection.JavaConversions._
import dispatch._
import net.liftweb.json._

case class WaitForElasticSearchClusterGreen(packageName: String, stage: Stage, duration: Long)
  extends ASGTask with RepeatedPollingCheck {

  val description = "Wait for the elasticsearch cluster status to be green"
  override val verbose =
    """Minimise thrashing while rebalancing by waiting until the elasticsearch cluster status is green.
      |Requires access to port 9200 on cluster members.
    """.stripMargin

  def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing) {
    val instance = EC2(asg.getInstances().headOption.getOrElse {
      throw new IllegalArgumentException("Auto-scaling group: %s had no instances" format (asg))
    })
    val node = ElasticSearchNode(instance.getPublicDnsName)
    check {
      node.clusterIsHealthy && node.dataNodesInCluster == refresh(asg).getDesiredCapacity
    }
  }
}

case class CullElasticSearchInstancesWithTerminationTag(packageName: String, stage: Stage, duration: Long)
  extends ASGTask with RepeatedPollingCheck{

  def execute(asg: AutoScalingGroup)(implicit keyRing: KeyRing) {
    for (instance <- asg.getInstances) {
      if (EC2.hasTag(instance, "Magenta", "Terminate")) {
        val node = ElasticSearchNode(EC2(instance).getPublicDnsName)
        check {
          node.clusterIsHealthy && node.dataNodesInCluster == refresh(asg).getDesiredCapacity
        }
        node.shutdown()
        cull(asg, instance)
      }
    }
  }

  lazy val description = "Terminate instances with the termination tag for this deploy"
}

case class ElasticSearchNode(address: String) {
  implicit val format = DefaultFormats

  val http = new Http()
  private def clusterHealth = http(:/(address, 9200) / "_cluster" / "health" >- {json =>
    parse(json)
  })

  def dataNodesInCluster = (clusterHealth \ "number_of_data_nodes").extract[Int]
  def clusterIsHealthy = (clusterHealth \ "status").extract[String] == "green"

  def shutdown() = http((:/(address, 9200) / "_cluster" / "nodes" / "_local" / "_shutdown").POST >|)
}


