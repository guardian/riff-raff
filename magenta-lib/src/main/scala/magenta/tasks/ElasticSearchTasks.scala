package magenta.tasks

import magenta._
import com.amazonaws.services.autoscaling.model.{AutoScalingGroup, Instance}
import collection.JavaConversions._
import dispatch.classic._
import org.json4s._
import org.json4s.native.JsonMethods._
import java.net.ConnectException
import magenta.DeploymentPackage
import magenta.KeyRing
import magenta.Stage

case class WaitForElasticSearchClusterGreen(pkg: DeploymentPackage, stage: Stage, stack: Stack, duration: Long)
                                           (implicit val keyRing: KeyRing)
  extends ASGTask with RepeatedPollingCheck {

  val description = "Wait for the elasticsearch cluster status to be green"
  override val verbose =
    """Minimise thrashing while rebalancing by waiting until the elasticsearch cluster status is green.
      |Requires access to port 9200 on cluster members.
    """.stripMargin

  override def execute(asg: AutoScalingGroup, reporter: DeployReporter, stopFlag: => Boolean) {
    val instance = EC2(asg.getInstances().headOption.getOrElse {
      throw new IllegalArgumentException("Auto-scaling group: %s had no instances" format (asg))
    })
    val node = ElasticSearchNode(instance.getPublicDnsName)
    check(reporter, stopFlag) {
      node.inHealthyClusterOfSize(refresh(asg).getDesiredCapacity)
    }
  }
}

case class CullElasticSearchInstancesWithTerminationTag(pkg: DeploymentPackage, stage: Stage, stack: Stack, duration: Long)
                                                       (implicit val keyRing: KeyRing)
  extends ASGTask with RepeatedPollingCheck{

  override def execute(asg: AutoScalingGroup, reporter: DeployReporter, stopFlag: => Boolean) {
    val newNode = asg.getInstances.filterNot(EC2.hasTag(_, "Magenta", "Terminate")).head
    val newESNode = ElasticSearchNode(EC2(newNode).getPublicDnsName)

    def cullInstance(instance: Instance) {
        val node = ElasticSearchNode(EC2(instance).getPublicDnsName)
        check(reporter, stopFlag) {
          newESNode.inHealthyClusterOfSize(refresh(asg).getDesiredCapacity)
        }
        if (!stopFlag) {
          node.shutdown()
          check(reporter, stopFlag) {
            newESNode.inHealthyClusterOfSize(refresh(asg).getDesiredCapacity - 1)
          }
        }
        if (!stopFlag) cull(asg, instance)
    }

    val instancesToKill = asg.getInstances.filter(instance => EC2.hasTag(instance, "Magenta", "Terminate"))
    val orderedInstancesToKill = instancesToKill.transposeBy(_.getAvailabilityZone)
    orderedInstancesToKill.foreach(cullInstance)
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

  def inHealthyClusterOfSize(desiredClusterSize: Int) =
    try {
      clusterIsHealthy && dataNodesInCluster == desiredClusterSize
    } catch {
      case e: ConnectException => false
    }

  def shutdown() = http((:/(address, 9200) / "_cluster" / "nodes" / "_local" / "_shutdown").POST >|)
}


